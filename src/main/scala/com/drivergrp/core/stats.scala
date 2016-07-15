package com.drivergrp.core

import com.drivergrp.core.logging.LoggerModule
import com.drivergrp.core.time.{Time, TimeRange}

object stats {

  type StatsKey = String
  type StatsKeys = Seq[StatsKey]


  trait StatsModule {

    def stats: Stats
  }

  trait Stats {

    def recordStats(keys: StatsKeys, interval: TimeRange, value: BigDecimal): Unit

    def recordStats(keys: StatsKeys, interval: TimeRange, value: Int): Unit =
      recordStats(keys, interval, BigDecimal(value))

    def recordStats(key: StatsKey, interval: TimeRange, value: BigDecimal): Unit =
      recordStats(Vector(key), interval, value)

    def recordStats(key: StatsKey, interval: TimeRange, value: Int): Unit =
      recordStats(Vector(key), interval, BigDecimal(value))

    def recordStats(keys: StatsKeys, time: Time, value: BigDecimal): Unit =
      recordStats(keys, TimeRange(time, time), value)

    def recordStats(keys: StatsKeys, time: Time, value: Int): Unit =
      recordStats(keys, TimeRange(time, time), BigDecimal(value))

    def recordStats(key: StatsKey, time: Time, value: BigDecimal): Unit =
      recordStats(Vector(key), TimeRange(time, time), value)

    def recordStats(key: StatsKey, time: Time, value: Int): Unit =
      recordStats(Vector(key), TimeRange(time, time), BigDecimal(value))
  }

  trait LogStats extends Stats {
    this: LoggerModule =>

    def recordStats(keys: StatsKeys, interval: TimeRange, value: BigDecimal): Unit = {
      log.audit(s"${keys.mkString(".")}(${interval.start.millis}-${interval.end.millis})=${value.toString}")
    }
  }
}
