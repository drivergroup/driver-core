package com.drivergrp.core

import java.math.MathContext

import com.drivergrp.core.revision.Revision
import com.drivergrp.core.time.{Time, TimeRange}

import scala.reflect.ClassTag
import scala.util.Random

object generators {

  private val random = new Random
  import random._

  private val DefaultMaxLength = 100
  private val StringLetters    = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ ".toSet

  def nextId[T](): Id[T] = Id[T](scala.math.abs(nextLong()))

  def nextId[T](maxValue: Int): Id[T] = Id[T](scala.math.abs(nextInt(maxValue).toLong))

  def nextName[T](maxLength: Int = DefaultMaxLength): Name[T] = Name[T](nextString(maxLength))

  def nextUuid() = java.util.UUID.randomUUID

  def nextRevision[T]() = Revision[T](nextUuid().toString)

  def nextString(maxLength: Int = DefaultMaxLength): String =
    (oneOf[Char](StringLetters) +: arrayOf(oneOf[Char](StringLetters), maxLength - 1)).mkString

  def nextOption[T](value: => T): Option[T] = if (nextBoolean) Option(value) else None

  def nextPair[L, R](left: => L, right: => R): (L, R) = (left, right)

  def nextTriad[F, S, T](first: => F, second: => S, third: => T): (F, S, T) = (first, second, third)

  def nextTime(): Time = Time(math.abs(nextLong() % System.currentTimeMillis))

  def nextTimeRange(): TimeRange = {
    val oneTime     = nextTime()
    val anotherTime = nextTime()

    TimeRange(Time(scala.math.min(oneTime.millis, anotherTime.millis)),
              Time(scala.math.max(oneTime.millis, anotherTime.millis)))
  }

  def nextBigDecimal(multiplier: Double = 1000000.00, precision: Int = 2): BigDecimal =
    BigDecimal(multiplier * nextDouble, new MathContext(precision))

  def oneOf[T](items: T*): T = oneOf(items.toSet)

  def oneOf[T](items: Set[T]): T = items.toSeq(nextInt(items.size))

  def arrayOf[T: ClassTag](generator: => T, maxLength: Int = DefaultMaxLength): Array[T] =
    Array.fill(nextInt(maxLength))(generator)

  def seqOf[T](generator: => T, maxLength: Int = DefaultMaxLength): Seq[T] =
    Seq.fill(nextInt(maxLength))(generator)

  def vectorOf[T](generator: => T, maxLength: Int = DefaultMaxLength): Vector[T] =
    Vector.fill(nextInt(maxLength))(generator)

  def listOf[T](generator: => T, maxLength: Int = DefaultMaxLength): List[T] =
    List.fill(nextInt(maxLength))(generator)

  def setOf[T](generator: => T, maxLength: Int = DefaultMaxLength): Set[T] =
    seqOf(generator, maxLength).toSet

  def mapOf[K, V](maxLength: Int, keyGenerator: => K, valueGenerator: => V): Map[K, V] =
    seqOf(nextPair(keyGenerator, valueGenerator), maxLength).toMap
}
