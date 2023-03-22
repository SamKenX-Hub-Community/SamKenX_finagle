package com.twitter.finagle.loadbalancer

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.Address
import com.twitter.finagle._
import com.twitter.finagle.addr.WeightedAddress
import com.twitter.finagle.client.utils.StringClient
import com.twitter.finagle.loadbalancer.LoadBalancerFactory.ErrorLabel
import com.twitter.finagle.param.Stats
import com.twitter.finagle.server.ServerInfo
import com.twitter.finagle.server.utils.StringServer
import com.twitter.finagle.stats.InMemoryHostStatsReceiver
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.util.Activity
import com.twitter.util.Await
import com.twitter.util.Awaitable
import com.twitter.util.Event
import com.twitter.util.Future
import com.twitter.util.Promise
import com.twitter.util.Var
import java.net.InetAddress
import java.net.InetSocketAddress
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.funsuite.AnyFunSuite

class LoadBalancerFactoryTest extends AnyFunSuite with Eventually with IntegrationPatience {
  val echoService = Service.mk[String, String](Future.value(_))

  private def await[T](t: Awaitable[T]): T = Await.result(t, 5.seconds)

  trait PerHostFlagCtx extends App {
    val label = "myclient"
    val client = StringClient.client.configured(param.Label(label))
    val port = "localhost:8080"
    val perHostStatKey = Seq(label, port, "available")
  }

  test("reports per-host stats when flag is true") {
    new PerHostFlagCtx {
      val sr = new InMemoryHostStatsReceiver
      val sr1 = new InMemoryStatsReceiver

      perHostStats.let(true) {
        client
          .configured(LoadBalancerFactory.HostStats(sr))
          .newService(port)
        eventually {
          assert(sr.self.gauges(perHostStatKey).apply == 1.0)
        }

        client
          .configured(LoadBalancerFactory.HostStats(sr1))
          .newService(port)
        eventually {
          assert(sr1.gauges(perHostStatKey).apply == 1.0)
        }
      }
    }
  }

  test("does not report per-host stats when flag is false") {
    new PerHostFlagCtx {
      val sr = new InMemoryHostStatsReceiver
      val sr1 = new InMemoryStatsReceiver

      perHostStats.let(false) {
        client
          .configured(LoadBalancerFactory.HostStats(sr))
          .newService(port)
        assert(sr.self.gauges.contains(perHostStatKey) == false)

        client
          .configured(LoadBalancerFactory.HostStats(sr1))
          .newService(port)
        assert(sr1.gauges.contains(perHostStatKey) == false)
      }
    }
  }

  test("reports with canonical name when formatter is ByCanonicalHostName") {
    new PerHostFlagCtx {
      val sr = new InMemoryHostStatsReceiver
      val sr1 = new InMemoryStatsReceiver

      val nonCanonicalPort: String = "github.com:443"
      val canonicalName = InetAddress.getByName("github.com").getCanonicalHostName
      val canonicalPerHostStatKey = Seq(label, s"${canonicalName}:443", "available")

      perHostStats.let(true) {
        client
          .configured(LoadBalancerFactory.HostStats(sr))
          .configured(LoadBalancerFactory.AddressFormatter.ByCanonicalHostName)
          .newService(nonCanonicalPort)
        eventually {
          assert(sr.self.gauges(canonicalPerHostStatKey).apply == 1.0)
        }

        client
          .configured(LoadBalancerFactory.HostStats(sr1))
          .configured(LoadBalancerFactory.AddressFormatter.ByCanonicalHostName)
          .newService(nonCanonicalPort)
        eventually {
          assert(sr1.gauges(canonicalPerHostStatKey).apply == 1.0)
        }
      }
    }
  }

  test("reports with given host name with default formatter ByHostName") {
    new PerHostFlagCtx {
      val sr = new InMemoryHostStatsReceiver
      val sr1 = new InMemoryStatsReceiver

      val nonCanonicalPort: String = "github.com:443"
      val nonCanonicalPerHostStatKey = Seq(label, nonCanonicalPort, "available")

      perHostStats.let(true) {
        client
          .configured(LoadBalancerFactory.HostStats(sr))
          .newService(nonCanonicalPort)
        eventually {
          assert(sr.self.gauges(nonCanonicalPerHostStatKey).apply == 1.0)
        }

        client
          .configured(LoadBalancerFactory.HostStats(sr1))
          .newService(nonCanonicalPort)
        eventually {
          assert(sr1.gauges(nonCanonicalPerHostStatKey).apply == 1.0)
        }
      }
    }
  }

  test("make service factory stack") {
    val addr1 = new InetSocketAddress(InetAddress.getLoopbackAddress, 0)
    val server1 = StringServer.server.serve(addr1, echoService)

    val addr2 = new InetSocketAddress(InetAddress.getLoopbackAddress, 0)
    val server2 = StringServer.server.serve(addr2, echoService)

    val dest = Name.bound(
      Address(server1.boundAddress.asInstanceOf[InetSocketAddress]),
      Address(server2.boundAddress.asInstanceOf[InetSocketAddress])
    )

    val sr = new InMemoryStatsReceiver
    val client = StringClient.client
      .configured(Stats(sr))
      .newService(dest, "client")

    assert(sr.counters(Seq("client", "loadbalancer", "adds")) == 2)
    assert(Await.result(client("hello\n")) == "hello")

    server1.close()
    server2.close()
  }

  test("throws NoBrokersAvailableException with negative addresses") {
    val next: Stack[ServiceFactory[String, String]] =
      Stack.leaf(
        Stack.Role("mock"),
        ServiceFactory.const[String, String](Service.mk[String, String](req => Future.value(req)))
      )

    val stack = new LoadBalancerFactory.StackModule[String, String] {
      val description = "mock"
    }.toStack(next)

    val dest = LoadBalancerFactory.Dest(Var(Addr.Neg))
    val label = "mystack"
    val factory = stack.make(Stack.Params.empty + dest + ErrorLabel(label))

    Dtab.unwind {
      val newDtab = Dtab.read("/foo => /bar")
      Dtab.local = newDtab
      val noBrokers = intercept[NoBrokersAvailableException](Await.result(factory()))
      assert(noBrokers.name == "mystack")
      assert(noBrokers.localDtab == newDtab)
    }

  }

  test("default address ordering") {
    val ordering = LoadBalancerFactory.AddressOrdering.param.default.ordering

    val ips: Seq[Array[Byte]] = (10 until 0 by -1).map { i => Array[Byte](10, 0, 0, i.toByte) }

    val addresses: Seq[Address.Inet] = ips.map { ip =>
      val inet = InetAddress.getByAddress(ip)
      Address.Inet(new InetSocketAddress(inet, 0), Addr.Metadata.empty)
    }

    assert(addresses.sorted(ordering) == addresses.sorted(ordering))

    // breaks ties via port
    val ip = Array[Byte](10, 0, 0, 1)
    val addr0 = Address(new InetSocketAddress(InetAddress.getByAddress(ip), 80))
    val addr1 = Address(new InetSocketAddress(InetAddress.getByAddress(ip), 8080))
    assert(Vector(addr1, addr0).sorted(ordering).last == addr1)

    val sorted = addresses.sorted(ordering)
    assert(sorted.indices.exists { i => sorted(i) != addresses(i) })

    val failed = Address.Failed(new Exception)
    val withFailed = failed +: addresses
    assert(withFailed.sorted(ordering).last == failed)

    val sf = Address.ServiceFactory(
      ServiceFactory.const[Int, Int](Service.mk[Int, Int](_ => ???)),
      Addr.Metadata.empty)
    val withSf = sf +: addresses
    assert(withSf.sorted(ordering).last == sf)

    val unresolved = Address(InetSocketAddress.createUnresolved("dest", 0))
    val withUnResolved = unresolved +: addresses
    assert(withUnResolved.sorted(ordering).head == unresolved)

    val all = unresolved +: failed +: sf +: addresses
    // it doesn't really matter which one comes last here
    assert(all.sorted(ordering).last == sf)
  }

  test("Respects the AddressOrdering") {
    val endpoint: Stack[ServiceFactory[String, String]] =
      Stack.leaf(
        Stack.Role("endpoint"),
        ServiceFactory.const[String, String](Service.mk[String, String](req => ???))
      )

    val stack = LoadBalancerFactory.module[String, String].toStack(endpoint)

    var eps: Vector[String] = Vector.empty
    val mockBalancer = new LoadBalancerFactory {
      def newBalancer[Req, Rep](
        endpoints: Activity[IndexedSeq[EndpointFactory[Req, Rep]]],
        emptyException: NoBrokersAvailableException,
        params: Stack.Params
      ): ServiceFactory[Req, Rep] = {
        eps = endpoints.sample().toVector.map(_.address.toString)
        ServiceFactory.const(Service.mk(_ => ???))
      }
    }

    val addresses = (10 to 0 by -1).map { i =>
      Address(InetSocketAddress.createUnresolved(s"inet-address-$i", 0))
    }

    var orderCalled = false
    val order: Ordering[Address] = new Ordering[Address] {
      def compare(a0: Address, a1: Address): Int = {
        orderCalled = true
        a0.toString.compare(a1.toString)
      }
    }

    stack.make(
      Stack.Params.empty +
        LoadBalancerFactory.Param(mockBalancer) +
        LoadBalancerFactory.Dest(Var(Addr.Bound(addresses.toSet))) +
        LoadBalancerFactory.AddressOrdering(order)
    )

    assert(orderCalled)
    val sortedAddresses: Seq[String] = addresses.sortBy(_.toString).map(_.toString)
    eps.indices.foreach { i => assert(eps(i) == sortedAddresses(i)) }
  }

  test("Respects ReplicateAddresses param") {
    val endpoint: Stack[ServiceFactory[String, String]] =
      Stack.leaf(
        Stack.Role("endpoint"),
        ServiceFactory.const[String, String](Service.mk[String, String](req => ???))
      )

    val stack = LoadBalancerFactory.module[String, String].toStack(endpoint)

    var eps: Set[Address] = Set.empty
    val mockBalancer = new LoadBalancerFactory {
      def newBalancer[Req, Rep](
        endpoints: Activity[IndexedSeq[EndpointFactory[Req, Rep]]],
        emptyException: NoBrokersAvailableException,
        params: Stack.Params
      ): ServiceFactory[Req, Rep] = {
        eps = endpoints.sample().toSet.map { ep: EndpointFactory[_, _] => ep.address }
        ServiceFactory.const(Service.mk(_ => ???))
      }
    }

    val size = 10
    val addresses = (0 until size).map { i =>
      Address(InetSocketAddress.createUnresolved(s"inet-address-$i", 0))
    }

    val replicateCount = 2
    stack.make(
      Stack.Params.empty +
        LoadBalancerFactory.Param(mockBalancer) +
        LoadBalancerFactory.Dest(Var(Addr.Bound(addresses.toSet))) +
        LoadBalancerFactory.ReplicateAddresses(replicateCount)
    )

    assert(eps.size == size * replicateCount)
  }

  test("Hydrates the LoadBalancerFactory.Endpoints when it's dehydrated") {
    val endpoint: Stack[ServiceFactory[String, String]] =
      Stack.leaf(
        Stack.Role("endpoint"),
        ServiceFactory.const[String, String](Service.mk[String, String](req => ???))
      )
    val size = 10

    var eps: Set[Address] = Set.empty
    val addresses = (0 until size).map { i =>
      Address(InetSocketAddress.createUnresolved(s"inet-address-$i", 0))
    }.toSet
    val mockBalancer = new LoadBalancerFactory {
      def newBalancer[Req, Rep](
        endpoints: Activity[IndexedSeq[EndpointFactory[Req, Rep]]],
        emptyException: NoBrokersAvailableException,
        params: Stack.Params
      ): ServiceFactory[Req, Rep] = {
        eps = endpoints.sample().toSet.map { ep: EndpointFactory[_, _] => ep.address }
        ServiceFactory.const(Service.mk(_ => ???))
      }
    }
    val stack = LoadBalancerFactory.module[String, String].toStack(endpoint)

    stack.make(
      Stack.Params.empty +
        LoadBalancerFactory.Param(mockBalancer) +
        LoadBalancerFactory.Dest(Var(Addr.Bound(addresses)))
    )

    assert(addresses == eps)
  }

  test("Ignores LoadBalancerFactory.Dest when LoadBalancerFactory.Endpoints is provided") {
    val endpoint: Stack[ServiceFactory[String, String]] =
      Stack.leaf(
        Stack.Role("endpoint"),
        ServiceFactory.const[String, String](Service.mk[String, String](req => ???))
      )
    val size = 10

    var eps: Set[Address] = Set.empty
    val addresses = (0 until size).map { i =>
      Address(InetSocketAddress.createUnresolved(s"inet-address-$i", 0))
    }.toSet
    val augmentedAddresses = addresses +
      Address(InetSocketAddress.createUnresolved(s"inet-address-10", 0))
    val mockBalancer = new LoadBalancerFactory {
      def newBalancer[Req, Rep](
        endpoints: Activity[IndexedSeq[EndpointFactory[Req, Rep]]],
        emptyException: NoBrokersAvailableException,
        params: Stack.Params
      ): ServiceFactory[Req, Rep] = {
        eps = endpoints.sample().toSet.map { ep: EndpointFactory[_, _] => ep.address }
        ServiceFactory.const(Service.mk(_ => ???))
      }
    }
    val stack = LoadBalancerFactory.module[String, String].toStack(endpoint)

    val augmentedFactories = TrafficDistributor
      .weightEndpoints[String, String](
        Activity.value(augmentedAddresses),
        addr => ???,
        false
      ).asInstanceOf[Event[Activity.State[Set[EndpointFactory[_, _]]]]]

    stack.make(
      Stack.Params.empty +
        LoadBalancerFactory.Param(mockBalancer) +
        LoadBalancerFactory.Dest(Var(Addr.Bound(addresses))) +
        LoadBalancerFactory.Endpoints(augmentedFactories)
    )

    assert(augmentedAddresses == eps)
  }

  test(
    "does not wrap new Balancer in a Traffic Distributor when toggle is set to enable WeightedAperture") {
    val serverInfo: ServerInfo = new ServerInfo {
      def environment: Option[String] = Some("staging")
      def id: String = "testing"
      def instanceId: Option[Long] = None
      def clusterId: String = id
      def zone: Option[String] = Some("smf1")
    }
    ServerInfo.initialize(serverInfo)
    val endpoint: Stack[ServiceFactory[String, String]] =
      Stack.leaf(
        Stack.Role("endpoint"),
        ServiceFactory.const[String, String](Service.mk[String, String](req => ???))
      )

    val mockBalancer = new LoadBalancerFactory {
      override def supportsWeighted: Boolean = true

      def newBalancer[Req, Rep](
        endpoints: Activity[IndexedSeq[EndpointFactory[Req, Rep]]],
        emptyException: NoBrokersAvailableException,
        params: Stack.Params
      ): ServiceFactory[Req, Rep] = {
        ServiceFactory.const(Service.mk(_ => ???))
      }
    }

    val stack = LoadBalancerFactory.module[String, String].toStack(endpoint)

    val params = Stack.Params.empty +
      LoadBalancerFactory.Param(mockBalancer)

    val a: ServiceFactory[String, String] = stack.make(params)

    assert(!a.isInstanceOf[TrafficDistributor[String, String]])
  }

  test("WeightedAperture has weights available to it from address metadata") {
    val endpoint: Stack[ServiceFactory[String, String]] =
      Stack.leaf(
        Stack.Role("endpoint"),
        ServiceFactory.const[String, String](Service.mk[String, String](req => ???))
      )

    val futureWeights = Promise[Set[Double]]()
    val mockBalancer = new LoadBalancerFactory {
      override def supportsWeighted: Boolean = true

      def newBalancer[Req, Rep](
        endpoints: Activity[IndexedSeq[EndpointFactory[Req, Rep]]],
        emptyException: NoBrokersAvailableException,
        params: Stack.Params
      ): ServiceFactory[Req, Rep] = {
        futureWeights.setValue(endpoints.sample().toSet.map { ep: EndpointFactory[_, _] =>
          ep.weight
        })
        ServiceFactory.const(Service.mk(_ => ???))
      }
    }

    val stack = LoadBalancerFactory.module[String, String].toStack(endpoint)

    val addresses = Set(
      WeightedAddress(Address.Inet(new InetSocketAddress(5435), Map.empty), 2.0),
      Address.Inet(new InetSocketAddress(5434), Map.empty)
    )

    val params = Stack.Params.empty +
      LoadBalancerFactory.Dest(Var(Addr.Bound(addresses))) +
      LoadBalancerFactory.Param(mockBalancer)

    val a: ServiceFactory[String, String] = stack.make(params)

    val weights = await(futureWeights)
    assert(weights == Set(1.0, 2.0))

  }

  test("Default panic mode is FiftyPercentUnhealthy") {
    val endpoint: Stack[ServiceFactory[String, String]] =
      Stack.leaf(
        Stack.Role("endpoint"),
        ServiceFactory.const[String, String](Service.mk[String, String](_ => ???))
      )

    val stack = LoadBalancerFactory.module[String, String].toStack(endpoint)

    var eps: Vector[String] = Vector.empty
    val mockBalancer = new LoadBalancerFactory {
      def newBalancer[Req, Rep](
        endpoints: Activity[IndexedSeq[EndpointFactory[Req, Rep]]],
        emptyException: NoBrokersAvailableException,
        params: Stack.Params
      ): ServiceFactory[Req, Rep] = {
        eps = endpoints.sample().toVector.map(_.address.toString)
        assert(params[PanicMode].maxEffort == PanicMode.FiftyPercentUnhealthy.maxEffort)
        ServiceFactory.const(Service.mk(_ => ???))
      }
    }

    val addresses = (0 to 10).map { i =>
      Address(InetSocketAddress.createUnresolved(s"inet-address-$i", 0))
    }

    stack.make(
      Stack.Params.empty +
        LoadBalancerFactory.Param(mockBalancer) +
        LoadBalancerFactory.Dest(Var(Addr.Bound(addresses.toSet)))
    )

  }
}
