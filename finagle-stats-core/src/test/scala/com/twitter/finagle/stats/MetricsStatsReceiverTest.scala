package com.twitter.finagle.stats

import com.twitter.finagle.stats.Helpers._
import com.twitter.finagle.stats.MetricBuilder.CounterType
import com.twitter.finagle.stats.MetricBuilder.GaugeType
import com.twitter.finagle.stats.MetricBuilder.HistogramType
import com.twitter.finagle.stats.MetricBuilder.Identity
import com.twitter.finagle.stats.MetricBuilder.IdentityType
import com.twitter.finagle.stats.exp.Expression
import com.twitter.finagle.stats.exp.ExpressionSchema
import com.twitter.finagle.stats.exp.ExpressionSchemaKey
import com.twitter.finagle.stats.exp.HistogramComponent
import com.twitter.conversions.DurationOps._
import com.twitter.util.Time
import org.scalatest.funsuite.AnyFunSuite

object MetricsStatsReceiverTest {
  trait TestCtx {
    val suffix = "default"
    def addCounter(
      statsReceiver: StatsReceiver,
      name: Seq[String]
    ): Counter
    def addGauge(
      statsReceiver: StatsReceiver,
      name: Seq[String]
    )(
      f: => Float
    ): Gauge
    def addHisto(
      statsReceiver: StatsReceiver,
      name: Seq[String]
    ): Stat

    // rootReceiver needs to be isolated to each subclass.
    val rootReceiver = new MetricsStatsReceiver(Metrics.createDetached())

    def readGauge(metrics: MetricsStatsReceiver, name: String): Double =
      get(name, metrics.registry.gauges).value.doubleValue

    def readGaugeInRoot(name: String): Double = readGauge(rootReceiver, name)
    def readCounterInRoot(name: String): Long =
      get(name, rootReceiver.registry.counters).value
  }

  class PreSchemaCtx extends TestCtx {
    override val suffix = " with pre-schema methods"

    def addCounter(
      statsReceiver: StatsReceiver,
      name: Seq[String]
    ): Counter = statsReceiver.counter(name: _*)
    def addGauge(
      statsReceiver: StatsReceiver,
      name: Seq[String]
    )(
      f: => Float
    ): Gauge = statsReceiver.addGauge(name: _*)(f)
    def addHisto(
      statsReceiver: StatsReceiver,
      name: Seq[String]
    ): Stat = statsReceiver.stat(name: _*)
  }

  class SchemaCtx extends TestCtx {
    override val suffix = " with schema methods"

    def addCounter(
      statsReceiver: StatsReceiver,
      name: Seq[String]
    ) =
      statsReceiver.counter(MetricBuilder.newBuilder(CounterType).withName(name: _*))
    def addGauge(
      statsReceiver: StatsReceiver,
      name: Seq[String]
    )(
      f: => Float
    ) =
      statsReceiver.addGauge(MetricBuilder.newBuilder(GaugeType).withName(name: _*))(f)
    def addHisto(
      statsReceiver: StatsReceiver,
      name: Seq[String]
    ) =
      statsReceiver.stat(MetricBuilder.newBuilder(HistogramType).withName(name: _*))
  }
}

class MetricsStatsReceiverTest extends AnyFunSuite {
  import MetricsStatsReceiverTest._

  test("toString") {
    val sr = new MetricsStatsReceiver(Metrics.createDetached())
    assert("MetricsStatsReceiver" == sr.toString)
    assert("MetricsStatsReceiver/s1" == sr.scope("s1").toString)
    assert("MetricsStatsReceiver/s1/s2" == sr.scope("s1").scope("s2").toString)
  }

  test("testLockFreeStats") {
    val metrics = Metrics.createDetached()
    val sr = new MetricsStatsReceiver(metrics)
    val stat1 = sr.stat(
      MetricBuilder.forStat
        .withName("name1"))
    val stat2 = sr.stat(
      MetricBuilder.forStat
        .withName("name2")
        .withMetricUsageHints(Set(MetricUsageHint.HighContention)))

    val histograms = Time.withCurrentTimeFrozen { tc =>
      metrics.histograms
      stat1.add(2)
      stat2.add(2)
      tc.advance(60.seconds)
      metrics.histograms
    }

    assertResult(2)(histograms.size)
    histograms.foreach { histogram =>
      assertResult(1)(histogram.value.count)
      assertResult(2)(histogram.value.sum)
    }
  }

  def testMetricsStatsReceiver(ctx: TestCtx): Unit = {
    import ctx._

    // scalafix:off StoreGaugesAsMemberVariables
    test("MetricsStatsReceiver should store and read gauge into the root StatsReceiver" + suffix) {
      val x = 1.5f
      // gauges are weakly referenced by the registry so we need to keep a strong reference
      val g = addGauge(rootReceiver, Seq("my_gauge"))(x)
      assert(readGaugeInRoot("my_gauge") == x)
    }

    test("cumulative gauge is working" + suffix) {
      val x = 1
      val y = 2
      val z = 3
      val g1 = addGauge(rootReceiver, Seq("my_cumulative_gauge"))(x)
      val g2 = addGauge(rootReceiver, Seq("my_cumulative_gauge"))(y)
      val g3 = addGauge(rootReceiver, Seq("my_cumulative_gauge"))(z)
      assert(readGaugeInRoot("my_cumulative_gauge") == x + y + z)
    }

    test(
      "Ensure that we throw an exception with a counter and a gauge when rollup collides" + suffix) {
      val sr = new RollupStatsReceiver(rootReceiver)
      addCounter(sr, Seq("a", "b", "c")).incr()
      intercept[MetricCollisionException] {
        sr.addGauge("a", "b", "d") {
          3
        }
      }
    }

    test("Ensure that we throw an exception when rollup collides via scoping" + suffix) {
      val sr = new RollupStatsReceiver(rootReceiver)
      val newSr = sr.scope("a").scope("b")
      addCounter(newSr, Seq("c")).incr()
      intercept[MetricCollisionException] {
        addGauge(newSr, Seq("d")) {
          3
        }
      }
    }

    test("reading histograms initializes correctly" + suffix) {
      val sr = new MetricsStatsReceiver(Metrics.createDetached())
      val name = "my_cool_stat"
      val stat = addHisto(sr, Seq(name))

      val reader = sr.registry.histoDetails.get(name)
      assert(reader != null && reader.counts == Nil)

      // also ensure the buckets were populated with default values.
      assert(get(name, sr.registry.histograms).value.percentiles.length == 6)
    }

    test("store and read counter into the root StatsReceiver" + suffix) {
      addCounter(rootReceiver, Seq("my_counter")).incr()
      assert(readCounterInRoot("my_counter") == 1)
    }

    test("separate gauge/stat/metric between detached Metrics and root Metrics" + suffix) {
      val detachedReceiver = new MetricsStatsReceiver(Metrics.createDetached())
      val g1 = addGauge(detachedReceiver, Seq("xxx"))(1.0f)
      val g2 = addGauge(rootReceiver, Seq("xxx"))(2.0f)
      assert(readGauge(detachedReceiver, "xxx") != readGauge(rootReceiver, "xxx"))
    }

    test("StatsReceivers share underlying metrics maps by default" + suffix) {
      val metrics1 = new Metrics()
      val metrics2 = new Metrics()

      val sr1 = new MetricsStatsReceiver(metrics1)
      val sr2 = new MetricsStatsReceiver(metrics2)

      addCounter(sr1, Seq("foo"))
      assert(find("foo", metrics1.counters).isDefined)
      assert(find("foo", metrics2.counters).isDefined)

      addGauge(sr1, Seq("bar"))(1f)
      assert(find("bar", metrics1.gauges).isDefined)
      assert(find("bar", metrics2.gauges).isDefined)

      addHisto(sr1, Seq("baz"))
      assert(find("baz", metrics1.histograms).isDefined)
      assert(find("baz", metrics2.histograms).isDefined)
    }

    test("StatsReceivers share underlying schema maps by default" + suffix) {
      val metrics1 = new Metrics()
      val metrics2 = new Metrics()

      val sr1 = new MetricsStatsReceiver(metrics1)
      val sr2 = new StatsReceiverProxy {
        protected def self: StatsReceiver = new MetricsStatsReceiver(metrics2)
      }

      addCounter(sr1, Seq("aaa"))
      assert(metrics1.schemas.containsKey("aaa"))
      assert(metrics2.schemas.containsKey("aaa"))

      addGauge(sr1, Seq("bbb"))(1f)
      assert(metrics1.schemas.containsKey("bbb"))
      assert(metrics2.schemas.containsKey("bbb"))

      addHisto(sr1, Seq("ccc"))
      assert(metrics1.schemas.containsKey("ccc"))
      assert(metrics2.schemas.containsKey("ccc"))
    }

    test("StatsReceiver metrics expose the underlying schema" + suffix) {
      val metrics = Metrics.createDetached()

      val sr = new MetricsStatsReceiver(metrics)
      val counter = addCounter(sr, Seq("aaa"))
      assert(metrics.schemas.get("aaa") == counter.metadata)

      val gauge = addGauge(sr, Seq("bbb"))(1f)
      assert(metrics.schemas.get("bbb") == gauge.metadata)

      val histo = addHisto(sr, Seq("ccc"))
      assert(metrics.schemas.get("ccc") == histo.metadata)
    }

    // scalafix:on StoreGaugesAsMemberVariables
  }

  testMetricsStatsReceiver(new PreSchemaCtx())
  testMetricsStatsReceiver(new SchemaCtx())

  test("expressions are reloaded with fully scoped names") {
    val metrics = Metrics.createDetached()
    val sr = new MetricsStatsReceiver(metrics)

    val aCounter = sr.scope("test").counter("a")
    val bHisto = sr.scope("test").stat("b")
    val cGauge = sr.scope(("test")).addGauge("c") { 1 }

    sr.registerExpression(
      ExpressionSchema(
        "test_expression",
        Expression(aCounter.metadata).plus(Expression(bHisto.metadata, HistogramComponent.Min)
          .plus(Expression(cGauge.metadata)))
      ))

    // what we expected as hydrated metric builders
    val aaSchema =
      MetricBuilder(metricType = CounterType)
        .withIdentity(Identity(Seq("test", "a"), Seq("a")))
        .withHierarchicalOnly
    val bbSchema =
      MetricBuilder(percentiles = BucketedHistogram.DefaultQuantiles, metricType = HistogramType)
        .withIdentity(Identity(Seq("test", "b"), Seq("b")))
        .withHierarchicalOnly
    val ccSchema =
      MetricBuilder(metricType = GaugeType)
        .withIdentity(Identity(Seq("test", "c"), Seq("c")))
        .withHierarchicalOnly

    val expected_expression = ExpressionSchema(
      "test_expression",
      Expression(aaSchema).plus(
        Expression(bbSchema, HistogramComponent.Min).plus(Expression(ccSchema))))

    assert(
      metrics.expressions
        .get(ExpressionSchemaKey("test_expression", Map(), Seq())).expr == expected_expression.expr)
  }

  test(
    "expressions with different serviceNames or different namespaces are stored without clobbering each other") {
    val metrics = Metrics.createDetached()
    val sr = new MetricsStatsReceiver(metrics)
    val exporter = new MetricsExporter(metrics)
    val aCounter =
      MetricBuilder(name = Seq("a"), metricType = CounterType)

    sr.registerExpression(ExpressionSchema("test_expression", Expression(aCounter)))
    assert(exporter.expressions.keySet.size == 1)

    sr.registerExpression(
      ExpressionSchema("test_expression", Expression(aCounter))
        .withServiceName("thrift"))
    assert(exporter.expressions.keySet.size == 2)

    sr.registerExpression(
      ExpressionSchema("test_expression", Expression(aCounter))
        .withNamespace("a", "b"))
    assert(exporter.expressions.keySet.size == 3)

    sr.registerExpression(
      ExpressionSchema("test_expression", Expression(aCounter))
        .withNamespace("a", "b").withServiceName("thrift"))
    assert(exporter.expressions.keySet.size == 4)
  }

  test("expressions with the key - name, labels and namespaces") {
    val metrics = Metrics.createDetached()
    val sr = new MetricsStatsReceiver(metrics)
    val exporter = new MetricsExporter(metrics)
    val aCounter =
      MetricBuilder(name = Seq("a"), metricType = CounterType)

    val bCounter =
      MetricBuilder(name = Seq("b"), metricType = CounterType)

    val expression1 = sr.registerExpression(
      ExpressionSchema("test_expression", Expression(aCounter))
        .withNamespace("a", "b"))
    assert(exporter.expressions.keySet.size == 1)

    val expression2 = sr.registerExpression(
      ExpressionSchema("test_expression", Expression(bCounter))
        .withNamespace("a", "b"))
    assert(exporter.expressions.keySet.size == 1)

    assert(exporter.expressions.values.head.expr == Expression(aCounter))
    assert(expression1.isReturn)
    assert(expression2.isThrow)
  }

  test("identity type is resolved to HierarchicalOnly if it is undetermined") {
    val metrics = Metrics.createDetached()
    val sr = new MetricsStatsReceiver(metrics)
    val counter = sr.counter("counter")
    val gauge = sr.addGauge("gauge") { 1.0f }
    val stat = sr.stat("stat")

    assert(
      counter.metadata.toMetricBuilder.get.identity.identityType == IdentityType.HierarchicalOnly)
    assert(
      gauge.metadata.toMetricBuilder.get.identity.identityType == IdentityType.HierarchicalOnly)
    assert(stat.metadata.toMetricBuilder.get.identity.identityType == IdentityType.HierarchicalOnly)
  }

  test("identity type of Hierarchical only is not modified") {
    val metrics = Metrics.createDetached()
    val sr = new MetricsStatsReceiver(metrics)
    val counter = sr.counter(MetricBuilder.forCounter.withName("counter").withHierarchicalOnly)
    val gauge = sr.addGauge(MetricBuilder.forGauge.withName("gauge").withHierarchicalOnly) { 1.0f }
    val stat = sr.stat(MetricBuilder.forStat.withName("stat").withHierarchicalOnly)

    assert(
      counter.metadata.toMetricBuilder.get.identity.identityType == IdentityType.HierarchicalOnly)
    assert(
      gauge.metadata.toMetricBuilder.get.identity.identityType == IdentityType.HierarchicalOnly)
    assert(stat.metadata.toMetricBuilder.get.identity.identityType == IdentityType.HierarchicalOnly)
  }

  test("identity type of Full only is not modified") {
    val metrics = Metrics.createDetached()
    val sr = new MetricsStatsReceiver(metrics)
    val counter = sr.counter(MetricBuilder.forCounter.withName("counter").withDimensionalSupport)
    val gauge = sr.addGauge(MetricBuilder.forGauge.withName("gauge").withDimensionalSupport) {
      1.0f
    }
    val stat = sr.stat(MetricBuilder.forStat.withName("stat").withDimensionalSupport)

    assert(counter.metadata.toMetricBuilder.get.identity.identityType == IdentityType.Full)
    assert(gauge.metadata.toMetricBuilder.get.identity.identityType == IdentityType.Full)
    assert(stat.metadata.toMetricBuilder.get.identity.identityType == IdentityType.Full)
  }
}
