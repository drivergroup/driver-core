package xyz.driver.core

import java.net.InetAddress
import java.util.{TimeZone, UUID}

import enumeratum._
import eu.timepit.refined.api.{Refined, Validate}
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import spray.json._
import xyz.driver.core.auth.AuthCredentials
import xyz.driver.core.date.{Date, DayOfWeek, Month}
import xyz.driver.core.domain.{Email, PhoneNumber}
import xyz.driver.core.rest.errors._
import xyz.driver.core.time.{Time, TimeOfDay}
import scala.reflect.runtime.universe._
import scala.util.Try

trait CoreJsonFormats extends DerivedJsonProtocol {

  implicit def idFormat[T]: RootJsonFormat[Id[T]] = new RootJsonFormat[Id[T]] {
    def write(id: Id[T]) = JsString(id.value)

    def read(value: JsValue): Id[T] = value match {
      case JsString(id) if Try(UUID.fromString(id)).isSuccess => Id[T](id.toLowerCase)
      case JsString(id)                                       => Id[T](id)
      case _                                                  => throw DeserializationException("Id expects string")
    }
  }

  @deprecated(
    "Tagged types will be removed. Please open an issue in case they are needed for your use-case.",
    "driver-core 1.11.5")
  implicit def taggedFormat[F, T](implicit underlying: JsonFormat[F]): JsonFormat[F @@ T] = new JsonFormat[F @@ T] {
    import tagging._
    override def write(obj: F @@ T): JsValue = underlying.write(obj)
    override def read(json: JsValue): F @@ T = underlying.read(json).tagged[T]
  }

  implicit def nameFormat[T]: RootJsonFormat[Name[T]] = new RootJsonFormat[Name[T]] {
    def write(name: Name[T]) = JsString(name.value)
    def read(value: JsValue): Name[T] = value match {
      case JsString(name) => Name[T](name)
      case _              => throw DeserializationException("Name expects string")
    }
  }

  implicit val timeFormat: RootJsonFormat[Time] = new RootJsonFormat[Time] {
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

  implicit val timeOfDayFormat: RootJsonFormat[TimeOfDay] = jsonFormat[TimeOfDay]

  implicit val dayOfWeekFormat: JsonFormat[DayOfWeek] = new EnumJsonFormat(DayOfWeek)

  implicit val dateFormat: RootJsonFormat[Date] = new RootJsonFormat[Date] {
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

  implicit val monthFormat: RootJsonFormat[Month] = new RootJsonFormat[Month] {
    def write(month: Month) = JsNumber(month)
    def read(value: JsValue): Month = value match {
      case JsNumber(month) if 0 <= month && month <= 11 => Month(month.toInt)
      case _                                            => throw DeserializationException("Expected a number from 0 to 11")
    }
  }

  implicit def revisionFormat[T]: RootJsonFormat[Revision[T]] = new RootJsonFormat[Revision[T]] {
    def write(revision: Revision[T]) = JsString(revision.id.toString)

    def read(value: JsValue): Revision[T] = value match {
      case JsString(revision) => Revision[T](revision)
      case _                  => throw DeserializationException("Revision expects uuid string")
    }
  }

  implicit val base64Format: RootJsonFormat[Base64] = new RootJsonFormat[Base64] {
    def write(base64Value: Base64) = JsString(base64Value.value)

    def read(value: JsValue): Base64 = value match {
      case JsString(base64Value) => Base64(base64Value)
      case _                     => throw DeserializationException("Base64 format expects string")
    }
  }

  implicit val emailFormat: RootJsonFormat[Email] = new RootJsonFormat[Email] {
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

  implicit val phoneNumberFormat: RootJsonFormat[PhoneNumber] = jsonFormat[PhoneNumber]

  implicit val authCredentialsFormat: RootJsonFormat[AuthCredentials] = new RootJsonFormat[AuthCredentials] {
    override def read(json: JsValue): AuthCredentials = {
      json match {
        case JsObject(fields) =>
          val emailField      = fields.get("email")
          val identifierField = fields.get("identifier")
          val passwordField   = fields.get("password")

          (emailField, identifierField, passwordField) match {
            case (_, _, None) =>
              deserializationError("password field must be set")
            case (Some(JsString(em)), _, Some(JsString(pw))) =>
              val email = Email.parse(em).getOrElse(throw deserializationError(s"failed to parse email $em"))
              AuthCredentials(email.toString, pw)
            case (_, Some(JsString(id)), Some(JsString(pw))) => AuthCredentials(id.toString, pw.toString)
            case (None, None, _)                             => deserializationError("identifier must be provided")
            case _                                           => deserializationError(s"failed to deserialize ${json.prettyPrint}")
          }
        case _ => deserializationError(s"failed to deserialize ${json.prettyPrint}")
      }
    }

    override def write(obj: AuthCredentials): JsValue = JsObject(
      "identifier" -> JsString(obj.identifier),
      "password"   -> JsString(obj.password)
    )
  }

  trait HasJsonFormat[T <: EnumEntry] { enum: Enum[T] =>
    implicit val format: JsonFormat[T] = new EnumJsonFormat(enum)
  }

  class EnumJsonFormat[T <: EnumEntry](enum: Enum[T]) extends JsonFormat[T] {
    override def read(json: JsValue): T = json match {
      case JsString(name) => enum.withNameOption(name).getOrElse(CoreJsonFormats.unrecognizedValue(name, enum.values))
      case _              => deserializationError("Expected string as enumeration value, but got " + json.toString)
    }

    override def write(obj: T): JsValue = JsString(obj.entryName)
  }

  class EnumJsonFormat2[T](mapping: (String, T)*) extends RootJsonFormat[T] {
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

  implicit def nonEmptyNameFormat[T](
      implicit nonEmptyStringFormat: JsonFormat[Refined[String, NonEmpty]]): RootJsonFormat[NonEmptyName[T]] =
    new RootJsonFormat[NonEmptyName[T]] {
      def write(name: NonEmptyName[T]) = JsString(name.value.value)

      def read(value: JsValue): NonEmptyName[T] =
        NonEmptyName[T](nonEmptyStringFormat.read(value))
    }

  implicit val serviceExceptionFormat: RootJsonFormat[ServiceException] =
    GadtJsonFormat.create[ServiceException]("type") {
      case _: InvalidInputException           => "InvalidInputException"
      case _: InvalidActionException          => "InvalidActionException"
      case _: ResourceNotFoundException       => "ResourceNotFoundException"
      case _: ExternalServiceException        => "ExternalServiceException"
      case _: ExternalServiceTimeoutException => "ExternalServiceTimeoutException"
      case _: DatabaseException               => "DatabaseException"
    } {
      case "InvalidInputException"     => jsonFormat(InvalidInputException, "message")
      case "InvalidActionException"    => jsonFormat(InvalidActionException, "message")
      case "ResourceNotFoundException" => jsonFormat(ResourceNotFoundException, "message")
      case "ExternalServiceException" =>
        jsonFormat(ExternalServiceException, "serviceName", "serviceMessage", "serviceException")
      case "ExternalServiceTimeoutException" => jsonFormat(ExternalServiceTimeoutException, "message")
      case "DatabaseException"               => jsonFormat(DatabaseException, "message")
    }
}

private[core] object CoreJsonFormats {
  def unrecognizedValue(value: String, possibleValues: Seq[Any]): Nothing =
    deserializationError(s"Unexpected value $value. Expected one of: ${possibleValues.mkString("[", ", ", "]")}")
}