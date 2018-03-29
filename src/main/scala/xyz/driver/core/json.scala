package xyz.driver.core

import java.net.InetAddress
import java.util.{TimeZone, UUID}

import scala.reflect.runtime.universe._
import scala.util.Try
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.PathMatcher.{Matched, Unmatched}
import akka.http.scaladsl.marshalling.{Marshaller, Marshalling}
import akka.http.scaladsl.unmarshalling.Unmarshaller
import enumeratum._
import spray.json._
import xyz.driver.core.auth.AuthCredentials
import xyz.driver.core.date.{Date, DayOfWeek, Month}
import xyz.driver.core.domain.{Email, PhoneNumber}
import xyz.driver.core.time.{Time, TimeOfDay}
import eu.timepit.refined.refineV
import eu.timepit.refined.api.{Refined, Validate}
import eu.timepit.refined.collection.NonEmpty

object json {
  import DefaultJsonProtocol._

  private def UuidInPath[T]: PathMatcher1[Id[T]] =
    PathMatchers.JavaUUID.map((id: UUID) => Id[T](id.toString.toLowerCase))

  def IdInPath[T]: PathMatcher1[Id[T]] = UuidInPath[T] | new PathMatcher1[Id[T]] {
    def apply(path: Path) = path match {
      case Path.Segment(segment, tail) => Matched(tail, Tuple1(Id[T](segment)))
      case _                           => Unmatched
    }
  }

  implicit def idFormat[T] = new RootJsonFormat[Id[T]] {
    def write(id: Id[T]) = JsString(id.value)

    def read(value: JsValue) = value match {
      case JsString(id) if Try(UUID.fromString(id)).isSuccess => Id[T](id.toLowerCase)
      case JsString(id)                                       => Id[T](id)
      case _                                                  => throw DeserializationException("Id expects string")
    }
  }

  def NameInPath[T]: PathMatcher1[Name[T]] = new PathMatcher1[Name[T]] {
    def apply(path: Path) = path match {
      case Path.Segment(segment, tail) => Matched(tail, Tuple1(Name[T](segment)))
      case _                           => Unmatched
    }
  }

  implicit def nameFormat[T] = new RootJsonFormat[Name[T]] {
    def write(name: Name[T]) = JsString(name.value)

    def read(value: JsValue): Name[T] = value match {
      case JsString(name) => Name[T](name)
      case _              => throw DeserializationException("Name expects string")
    }
  }

  def TimeInPath: PathMatcher1[Time] =
    PathMatcher("""[+-]?\d*""".r) flatMap { string =>
      try Some(Time(string.toLong))
      catch { case _: IllegalArgumentException => None }
    }

  implicit val timeFormat = new RootJsonFormat[Time] {
    def write(time: Time) = JsObject("timestamp" -> JsNumber(time.millis))

    def read(value: JsValue): Time = value match {
      case JsObject(fields) =>
        fields
          .get("timestamp")
          .flatMap {
            case JsNumber(millis) => Some(Time(millis.toLong))
            case _                => None
          }
          .getOrElse(throw DeserializationException("Time expects number"))
      case _ => throw DeserializationException("Time expects number")
    }
  }

  implicit object localTimeFormat extends JsonFormat[java.time.LocalTime] {
    private val formatter = TimeOfDay.getFormatter
    def read(json: JsValue): java.time.LocalTime = json match {
      case JsString(chars) =>
        java.time.LocalTime.parse(chars)
      case _ => deserializationError(s"Expected time string got ${json.toString}")
    }

    def write(obj: java.time.LocalTime): JsValue = {
      JsString(obj.format(formatter))
    }
  }

  implicit object timeZoneFormat extends JsonFormat[java.util.TimeZone] {
    override def write(obj: TimeZone): JsValue = {
      JsString(obj.getID())
    }

    override def read(json: JsValue): TimeZone = json match {
      case JsString(chars) =>
        java.util.TimeZone.getTimeZone(chars)
      case _ => deserializationError(s"Expected time zone string got ${json.toString}")
    }
  }

  implicit val timeOfDayFormat: RootJsonFormat[TimeOfDay] = jsonFormat2(TimeOfDay.apply)

  implicit val dayOfWeekFormat: JsonFormat[DayOfWeek] = new EnumeratumJsonFormat(DayOfWeek)

  implicit val dateFormat = new RootJsonFormat[Date] {
    def write(date: Date) = JsString(date.toString)
    def read(value: JsValue): Date = value match {
      case JsString(dateString) =>
        Date
          .fromString(dateString)
          .getOrElse(
            throw DeserializationException(s"Misformated ISO 8601 Date. Expected YYYY-MM-DD, but got $dateString."))
      case _ => throw DeserializationException(s"Date expects a string, but got $value.")
    }
  }

  implicit val monthFormat = new RootJsonFormat[Month] {
    def write(month: Month) = JsNumber(month)
    def read(value: JsValue): Month = value match {
      case JsNumber(month) if 0 <= month && month <= 11 => Month(month.toInt)
      case _                                            => throw DeserializationException("Expected a number from 0 to 11")
    }
  }

  def RevisionInPath[T]: PathMatcher1[Revision[T]] =
    PathMatcher("""[\da-fA-F]{8}-[\da-fA-F]{4}-[\da-fA-F]{4}-[\da-fA-F]{4}-[\da-fA-F]{12}""".r) flatMap { string =>
      Some(Revision[T](string))
    }

  implicit def revisionFromStringUnmarshaller[T]: Unmarshaller[String, Revision[T]] =
    Unmarshaller.strict[String, Revision[T]](Revision[T](_))

  implicit def revisionFormat[T] = new RootJsonFormat[Revision[T]] {
    def write(revision: Revision[T]) = JsString(revision.id.toString)

    def read(value: JsValue): Revision[T] = value match {
      case JsString(revision) => Revision[T](revision)
      case _                  => throw DeserializationException("Revision expects uuid string")
    }
  }

  implicit val base64Format = new RootJsonFormat[Base64] {
    def write(base64Value: Base64) = JsString(base64Value.value)

    def read(value: JsValue): Base64 = value match {
      case JsString(base64Value) => Base64(base64Value)
      case _                     => throw DeserializationException("Base64 format expects string")
    }
  }

  implicit val emailFormat = new RootJsonFormat[Email] {
    def write(email: Email) = JsString(email.username + "@" + email.domain)
    def read(json: JsValue): Email = json match {

      case JsString(value) =>
        Email.parse(value).getOrElse {
          deserializationError("Expected '@' symbol in email string as Email, but got " + json.toString)
        }

      case _ =>
        deserializationError("Expected string as Email, but got " + json.toString)
    }
  }

  implicit val phoneNumberFormat = jsonFormat2(PhoneNumber.apply)

  implicit val authCredentialsFormat = jsonFormat2(AuthCredentials)

  implicit object inetAddressFormat extends JsonFormat[InetAddress] {
    override def read(json: JsValue): InetAddress = json match {
      case JsString(ipString) =>
        Try(InetAddress.getByName(ipString))
          .getOrElse(deserializationError(s"Invalid IP Address: $ipString"))
      case _ => deserializationError(s"Expected string for IP Address, got $json")
    }

    override def write(obj: InetAddress): JsValue =
      JsString(obj.getHostAddress)
  }

  class EnumeratumJsonFormat[T <: EnumEntry](enum: Enum[T]) extends RootJsonFormat[T] {
    override def read(json: JsValue): T = json match {
      case JsString(name) =>
        enum
          .withNameOption(name)
          .getOrElse(
            throw DeserializationException(
              s"Value $name is not one of the possible values ${enum.values.mkString("[", ", ", "]")}"))
      case _ => deserializationError("Expected string as enumeration value, but got " + json.toString)
    }

    override def write(obj: T): JsValue = JsString(obj.entryName)
  }

  class EnumJsonFormat[T](mapping: (String, T)*) extends RootJsonFormat[T] {
    private val map = mapping.toMap

    override def write(value: T): JsValue = {
      map.find(_._2 == value).map(_._1) match {
        case Some(name) => JsString(name)
        case _          => serializationError(s"Value $value is not found in the mapping $map")
      }
    }

    override def read(json: JsValue): T = json match {
      case JsString(name) =>
        map.getOrElse(name, throw DeserializationException(s"Value $name is not found in the mapping $map"))
      case _ => deserializationError("Expected string as enumeration value, but got " + json.toString)
    }
  }

  class ValueClassFormat[T: TypeTag](writeValue: T => BigDecimal, create: BigDecimal => T) extends JsonFormat[T] {
    def write(valueClass: T) = JsNumber(writeValue(valueClass))
    def read(json: JsValue): T = json match {
      case JsNumber(value) => create(value)
      case _               => deserializationError(s"Expected number as ${typeOf[T].getClass.getName}, but got " + json.toString)
    }
  }

  class GadtJsonFormat[T: TypeTag](
      typeField: String,
      typeValue: PartialFunction[T, String],
      jsonFormat: PartialFunction[String, JsonFormat[_ <: T]])
      extends RootJsonFormat[T] {

    def write(value: T): JsValue = {

      val valueType = typeValue.applyOrElse(value, { v: T =>
        deserializationError(s"No Value type for this type of ${typeOf[T].getClass.getName}: " + v.toString)
      })

      val valueFormat =
        jsonFormat.applyOrElse(valueType, { f: String =>
          deserializationError(s"No Json format for this type of $valueType")
        })

      valueFormat.asInstanceOf[JsonFormat[T]].write(value) match {
        case JsObject(fields) => JsObject(fields ++ Map(typeField -> JsString(valueType)))
        case _                => serializationError(s"${typeOf[T].getClass.getName} serialized not to a JSON object")
      }
    }

    def read(json: JsValue): T = json match {
      case JsObject(fields) =>
        val valueJson = JsObject(fields.filterNot(_._1 == typeField))
        fields(typeField) match {
          case JsString(valueType) =>
            val valueFormat = jsonFormat.applyOrElse(valueType, { t: String =>
              deserializationError(s"Unknown ${typeOf[T].getClass.getName} type ${fields(typeField)}")
            })
            valueFormat.read(valueJson)
          case _ =>
            deserializationError(s"Unknown ${typeOf[T].getClass.getName} type ${fields(typeField)}")
        }
      case _ =>
        deserializationError(s"Expected Json Object as ${typeOf[T].getClass.getName}, but got " + json.toString)
    }
  }

  object GadtJsonFormat {

    def create[T: TypeTag](typeField: String)(typeValue: PartialFunction[T, String])(
        jsonFormat: PartialFunction[String, JsonFormat[_ <: T]]) = {

      new GadtJsonFormat[T](typeField, typeValue, jsonFormat)
    }
  }

  /**
    * Provides the JsonFormat for the Refined types provided by the Refined library.
    *
    * @see https://github.com/fthomas/refined
    */
  implicit def refinedJsonFormat[T, Predicate](
      implicit valueFormat: JsonFormat[T],
      validate: Validate[T, Predicate]): JsonFormat[Refined[T, Predicate]] =
    new JsonFormat[Refined[T, Predicate]] {
      def write(x: T Refined Predicate): JsValue = valueFormat.write(x.value)
      def read(value: JsValue): T Refined Predicate = {
        refineV[Predicate](valueFormat.read(value))(validate) match {
          case Right(refinedValue)   => refinedValue
          case Left(refinementError) => deserializationError(refinementError)
        }
      }
    }

  def NonEmptyNameInPath[T]: PathMatcher1[NonEmptyName[T]] = new PathMatcher1[NonEmptyName[T]] {
    def apply(path: Path) = path match {
      case Path.Segment(segment, tail) =>
        refineV[NonEmpty](segment) match {
          case Left(_)               => Unmatched
          case Right(nonEmptyString) => Matched(tail, Tuple1(NonEmptyName[T](nonEmptyString)))
        }
      case _ => Unmatched
    }
  }

  implicit def nonEmptyNameFormat[T](implicit nonEmptyStringFormat: JsonFormat[Refined[String, NonEmpty]]) =
    new RootJsonFormat[NonEmptyName[T]] {
      def write(name: NonEmptyName[T]) = JsString(name.value.value)

      def read(value: JsValue): NonEmptyName[T] =
        NonEmptyName[T](nonEmptyStringFormat.read(value))
    }

  val jsValueToStringMarshaller: Marshaller[JsValue, String] =
    Marshaller.strict[JsValue, String](value => Marshalling.Opaque[String](() => value.compactPrint))

  def valueToStringMarshaller[T](implicit jsonFormat: JsonWriter[T]): Marshaller[T, String] =
    jsValueToStringMarshaller.compose[T](jsonFormat.write)

  val stringToJsValueUnmarshaller: Unmarshaller[String, JsValue] =
    Unmarshaller.strict[String, JsValue](value => value.parseJson)

  def stringToValueUnmarshaller[T](implicit jsonFormat: JsonReader[T]): Unmarshaller[String, T] =
    stringToJsValueUnmarshaller.map[T](jsonFormat.read)
}
