package com.twitter.finagle.stats

import com.twitter.finagle.stats.MetricsView.CounterSnapshot
import com.twitter.finagle.stats.MetricsView.GaugeSnapshot
import com.twitter.finagle.stats.MetricsView.HistogramSnapshot
import com.twitter.finagle.stats.exp.ExpressionSchema.ExpressionCollisionException
import com.twitter.finagle.stats.exp._
import com.twitter.logging.Logger
import com.twitter.util.Return
import com.twitter.util.Throw
import com.twitter.util.Try
import com.twitter.util.lint.Category
import com.twitter.util.lint.Issue
import com.twitter.util.lint.Rule
import java.util
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

object Metrics {

  private val log = Logger.get()

  private val defaultHistogramFactory = (
    params: MetricsHistogramFactory.Params
  ) => {
    new MetricsBucketedHistogram(
      name = params.name,
      percentiles = params.percentiles,
      useLockFreeBucketedHistogram = params.hints.contains(MetricUsageHint.HighContention)
    )
  }

  // represents a real instance of a gauge or a counter
  private sealed trait Repr
  private case object GaugeRepr extends Repr
  private case object CounterRepr extends Repr

  private val DefaultMetricsMaps: MetricsMaps = newMetricsMaps

  /**
   * Create a new [[Metrics]] that does not share gauges, counters, or stats (by default,
   * these are shared across [[Metrics]] instances)
   */
  def createDetached(
    mkHistogram: (String, IndexedSeq[Double]) => MetricsHistogram,
    separator: String
  ): Metrics =
    new Metrics(mkHistogram, separator, newMetricsMaps)

  def createDetached(
    mkHistogram: MetricsHistogramFactory.Type,
    separator: String
  ): Metrics =
    new Metrics(mkHistogram, separator, newMetricsMaps)

  def createDetached(): Metrics =
    createDetached(Metrics.defaultHistogramFactory, scopeSeparator())

  private[this] def newMetricsMaps: MetricsMaps = MetricsMaps(
    countersMap = new ConcurrentHashMap[Seq[String], MetricsStore.StoreCounter](),
    statsMap = new ConcurrentHashMap[Seq[String], MetricsStore.StoreStat](),
    gaugesMap = new ConcurrentHashMap[Seq[String], MetricsStore.StoreGauge](),
    /** Store MetricSchemas for each metric in order to surface metric metadata to users. */
    metricSchemas = new ConcurrentHashMap[String, MetricBuilder](),
    expressionSchemas = new ConcurrentHashMap[ExpressionSchemaKey, ExpressionSchema]()
  )

  private class StoreCounterImpl(
    override val key: String,
    override val builder: MetricBuilder)
      extends MetricsStore.StoreCounter {
    private[this] val adder = new LongAdder()

    val counter: Counter = new Counter {
      def incr(delta: Long): Unit = {
        adder.add(delta)
      }

      def metadata: Metadata = builder
    }

    def count: Long = adder.sum()
  }

  private class StoreGaugeImpl(
    override val key: String,
    override val builder: MetricBuilder,
    f: => Number)
      extends MetricsStore.StoreGauge {
    override def read: Number = f
  }

  private class StoreStatImpl(
    histo: MetricsHistogram,
    override val key: String,
    override val builder: MetricBuilder,
    doLog: Boolean)
      extends MetricsStore.StoreStat {
    def snapshot: Snapshot = histo.snapshot

    override val stat: Stat = new Stat {
      def add(value: Float): Unit = {
        if (doLog)
          log.info(s"Stat ${key} observed $value")
        val asLong = value.toLong
        histo.add(asLong)
      }

      def metadata: Metadata = builder
    }

    def clear(): Unit = histo.clear()
  }

  private case class MetricsMaps(
    countersMap: ConcurrentHashMap[Seq[String], MetricsStore.StoreCounter],
    statsMap: ConcurrentHashMap[Seq[String], MetricsStore.StoreStat],
    gaugesMap: ConcurrentHashMap[Seq[String], MetricsStore.StoreGauge],
    metricSchemas: ConcurrentHashMap[String, MetricBuilder],
    expressionSchemas: ConcurrentHashMap[ExpressionSchemaKey, ExpressionSchema])
}

/**
 * Thrown when you try to create a metric which would collide with a pre-existing metric.
 */
private[stats] class MetricCollisionException(msg: String) extends IllegalArgumentException(msg)

/**
 * A concrete metrics registry for creating and reading metrics.
 *
 * This stats implementation respects [[Verbosity verbosity levels]] such that
 *
 *  - it takes [[Verbosity]] as an argument while creating a certain metric and
 *  - reports verbosity levels via [[MetricsView#verbosity]]
 *
 * @note For efficiency reasons, it doesn't keep track of default (i.e., [[Verbosity.Default]])
 *       metrics.
 *
 * @note A verbosity level is only attached once, when metric is being created. Any subsequent
 *       creation/querying of the same metric (i.e., metric with the same name), doesn't affect
 *       its initial verbosity.
 *
 * @note By default, instances of [[Metrics]] share underlying [[Metrics.MetricsMaps]]. In the
 *       case of multiple [[StatsReceiver]]s, this avoids duplicate metrics. To use per-instance
 *       [[Metrics.MetricsMaps]], create the instance using `Metrics.createDetached`.
 */
private[finagle] class Metrics private (
  histogramFactory: MetricsHistogramFactory.Type,
  separator: String,
  metricsMaps: Metrics.MetricsMaps)
    extends MetricsStore
    with MetricsView {

  def this(
    mkHistogram: (String, IndexedSeq[Double]) => MetricsHistogram,
    separator: String,
    metricsMaps: Metrics.MetricsMaps
  ) = this(
    params => mkHistogram(params.name, params.percentiles),
    separator,
    metricsMaps
  )
  def this() = this(Metrics.defaultHistogramFactory, scopeSeparator(), Metrics.DefaultMetricsMaps)

  def this(mkHistogram: (String, IndexedSeq[Double]) => MetricsHistogram, separator: String) =
    this(mkHistogram, separator, Metrics.DefaultMetricsMaps)

  import Metrics._

  private[this] val loggedStats: Set[String] = debugLoggedStatNames()

  private[this] val Metrics.MetricsMaps(
    countersMap,
    statsMap,
    gaugesMap,
    metricSchemas,
    expressionSchemas) = metricsMaps

  private[this] val reservedNames = new ConcurrentHashMap[String, Repr]()

  val histoDetails = new ConcurrentHashMap[String, HistogramDetail]

  private[this] def formatHierarchicalName(metricBuilder: MetricBuilder): String = {
    if (metricBuilder.isStandard) metricBuilder.name.mkString("/")
    else metricBuilder.name.mkString(separator)
  }

  def getOrCreateCounter(metricBuilder: MetricBuilder): MetricsStore.StoreCounter = {
    val counter = countersMap.get(metricBuilder.name)
    if (counter != null)
      return counter

    val formatted = formatHierarchicalName(metricBuilder)
    val curNameUsage = reservedNames.putIfAbsent(formatted, CounterRepr)

    if (curNameUsage == null || curNameUsage == CounterRepr) {
      val next = new Metrics.StoreCounterImpl(formatted, metricBuilder)
      val prev = countersMap.putIfAbsent(metricBuilder.name, next)

      if (prev != null) {
        prev
      } else {
        metricSchemas.put(formatted, metricBuilder)
        next
      }
    } else {
      throw new MetricCollisionException(
        s"A gauge with the name $formatted had already" +
          " been defined when you tried to add a new counter."
      )
    }
  }

  def getOrCreateStat(metricBuilder: MetricBuilder): MetricsStore.StoreStat = {
    val stat = statsMap.get(metricBuilder.name)
    if (stat != null)
      return stat

    if (metricBuilder.percentiles.isEmpty) {
      createStat(metricBuilder.withPercentiles(BucketedHistogram.DefaultQuantiles: _*))
    } else {
      createStat(metricBuilder)
    }
  }

  private def createStat(metricBuilder: MetricBuilder): MetricsStore.StoreStat = {
    val formatted = formatHierarchicalName(metricBuilder)
    val doLog = loggedStats.contains(formatted)
    val histogram = histogramFactory.apply(
      MetricsHistogramFactory.Params(
        name = formatted,
        percentiles = metricBuilder.percentiles,
        hints = metricBuilder.metricUsageHints
      ))

    histogram match {
      case histo: MetricsBucketedHistogram =>
        histoDetails.put(formatted, histo.histogramDetail)
      case _ =>
        log.debug(s"$formatted's histogram implementation doesn't support details")
    }

    val next = new Metrics.StoreStatImpl(histogram, formatted, metricBuilder, doLog)
    val prev = statsMap.putIfAbsent(metricBuilder.name, next)

    if (prev != null) {
      prev
    } else {
      metricSchemas.put(formatted, metricBuilder)
      next
    }
  }

  private[stats] def registerExpression(exprSchema: ExpressionSchema): Try[Unit] = {
    validateStringExpression(exprSchema.expr)
    if (expressionSchemas.putIfAbsent(exprSchema.schemaKey(), exprSchema) == null) {
      Return.Unit
    } else {
      Throw(
        ExpressionCollisionException(
          s"An expression with the key ${exprSchema.schemaKey()} had already been defined."))
    }
  }

  private def validateStringExpression(expr: Expression): Unit = {
    expr match {
      case StringExpression(expr, _) if !metricSchemas.containsKey(expr) =>
        log.debug(s"StringExpression $expr may not exist in metrics")
      case FunctionExpression(_, exprs) =>
        exprs.map(validateStringExpression(_))
      case _ =>
    }
  }

  def registerGauge(metricBuilder: MetricBuilder, f: => Float): Unit =
    registerNumberGauge(metricBuilder, f)

  def registerLongGauge(metricBuilder: MetricBuilder, f: => Long): Unit =
    registerNumberGauge(metricBuilder, f)

  private def registerNumberGauge(metricBuilder: MetricBuilder, f: => Number): Unit = {
    val formatted = formatHierarchicalName(metricBuilder)
    val curNameUsage = reservedNames.putIfAbsent(formatted, GaugeRepr)

    if (curNameUsage == null) {
      val next =
        new Metrics.StoreGaugeImpl(formatted, metricBuilder, f)
      gaugesMap.putIfAbsent(metricBuilder.name, next)
      metricSchemas.putIfAbsent(formatted, metricBuilder)
    } else if (curNameUsage == GaugeRepr) {
      // it should be impossible to collide with a gauge in finagle since
      // StatsReceiverWithCumulativeGauges already protects us.
      // we replace existing gauges to support commons metrics behavior.
      val next =
        new Metrics.StoreGaugeImpl(formatted, metricBuilder, f)
      gaugesMap.put(metricBuilder.name, next)
      metricSchemas.put(formatted, metricBuilder)
    } else {
      throw new MetricCollisionException(
        s"A Counter with the name $formatted had already" +
          " been defined when you tried to add a new gauge."
      )
    }
  }

  def unregisterGauge(metricBuilder: MetricBuilder): Unit = {
    gaugesMap.remove(metricBuilder.name)
    val formatted = formatHierarchicalName(metricBuilder)
    metricSchemas.remove(formatted)
    reservedNames.remove(formatted)
  }

  def gauges: Iterable[GaugeSnapshot] = {
    val result = ArrayBuffer.empty[GaugeSnapshot]
    result.sizeHint(gaugesMap.size)

    gaugesMap.forEach { (_, sg) =>
      try {
        result += GaugeSnapshot(
          hierarchicalName = sg.key,
          builder = sg.builder,
          value = sg.read
        )
      } catch {
        case NonFatal(e) =>
          log.warning(e, s"exception while sampling gauge '${sg.key}'")
      }
    }
    result
  }

  def counters: Iterable[CounterSnapshot] = {
    val result = ArrayBuffer.empty[CounterSnapshot]
    result.sizeHint(countersMap.size)
    countersMap.forEach { (_, sc) =>
      result += CounterSnapshot(hierarchicalName = sc.key, builder = sc.builder, value = sc.count)
    }
    result
  }

  def histograms: Iterable[HistogramSnapshot] = {
    val result = ArrayBuffer.empty[HistogramSnapshot]
    result.sizeHint(statsMap.size)
    statsMap.forEach { (_, ss) =>
      result += HistogramSnapshot(
        hierarchicalName = ss.key,
        builder = ss.builder,
        value = ss.snapshot)
    }
    result
  }

  def schemas: util.Map[String, MetricBuilder] =
    util.Collections.unmodifiableMap(metricSchemas)

  def expressions: util.Map[ExpressionSchemaKey, ExpressionSchema] =
    util.Collections.unmodifiableMap(expressionSchemas)

  def metricsCollisionsLinterRule: Rule =
    Rule(
      Category.Configuration,
      "Metrics name collision",
      "Identifies metrics with ambiguous names that collide with other metrics. " +
        """Metrics recorded in a scope Seq("foo", "bar") can collide with Seq("foo/bar") when """ +
        s"exporting the metrics to JSON. To fix, never use the separator character $separator " +
        "in metrics names.\nThis linter does not account for denylisted metrics, verbosity, " +
        "or collisions between Stats and Counters/Gauges."
    ) {
      def toIssue(kind: String, collisions: Iterable[Seq[String]]) =
        Issue(
          collisions
            .map(_.mkString("Seq(\"", "\", \"", "\")"))
            .mkString(s"$kind:\n", " collides with\n", "")
        )

      def toMapWithIssues[V <: MetricsStore.StoreMetric](
        map: ConcurrentHashMap[Seq[String], V],
        namesToIssue: Iterable[Seq[String]] => Issue
      ) =
        map.asScala
          .groupBy { case (_, metric) => metric.key }
          .values
          .filter(_.size > 1)
          .map(collisions => namesToIssue(collisions.keys))

      toMapWithIssues(gaugesMap, toIssue("Gauge", _)).toSeq ++
        toMapWithIssues(countersMap, toIssue("Counter", _)) ++
        toMapWithIssues(statsMap, toIssue("Stat", _))
    }
}
