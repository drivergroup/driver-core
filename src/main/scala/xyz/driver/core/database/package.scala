package xyz.driver.core

import java.sql.{Date => SqlDate}
import java.util.Calendar

import date.{Date, Month}
import slick.dbio._
import slick.jdbc.JdbcProfile

package object database {

  type Schema = {
    def create: DBIOAction[Unit, NoStream, Effect.Schema]
    def drop: DBIOAction[Unit, NoStream, Effect.Schema]
  }

  type GeneratedTables = {
    // structure of Slick data model traits generated by sbt-slick-codegen
    val profile: JdbcProfile
    def schema: profile.SchemaDescription
  }

  private[database] def sqlDateToDate(sqlDate: SqlDate): Date = {
    // NOTE: SQL date does not have a time component, so this date
    // should only be interpreted in the running JVMs timezone.
    val cal = Calendar.getInstance()
    cal.setTime(sqlDate)
    Date(cal.get(Calendar.YEAR), Month(cal.get(Calendar.MONTH)), cal.get(Calendar.DAY_OF_MONTH))
  }

  private[database] def dateToSqlDate(date: Date): SqlDate = {
    val cal = Calendar.getInstance()
    cal.set(date.year, date.month, date.day, 0, 0, 0)
    new SqlDate(cal.getTime.getTime)
  }

  @deprecated("Dal is deprecated. Please use Repository trait instead!", "1.8.26")
  type Dal = Repository

  @deprecated("SlickDal is deprecated. Please use SlickRepository class instead!", "1.8.26")
  type SlickDal = SlickRepository

  @deprecated("FutureDal is deprecated. Please use FutureRepository class instead!", "1.8.26")
  type FutureDal = FutureRepository
}
