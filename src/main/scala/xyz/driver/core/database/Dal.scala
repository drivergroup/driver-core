package xyz.driver.core.database

import scala.concurrent.{ExecutionContext, Future}

import scalaz.{ListT, Monad, OptionT}
import scalaz.std.scalaFuture._

trait Dal {
  protected type T[D]
  protected implicit val monadT: Monad[T]

  protected def execute[D](operations: T[D]): Future[D]
  protected def execute[D](readOperations: OptionT[T, D]): OptionT[Future, D]
  protected def execute[D](readOperations: ListT[T, D]): ListT[Future, D]
  protected def noAction[V](v: V): T[V]
  protected def customAction[R](action: => Future[R]): T[R]
}

class FutureDal(executionContext: ExecutionContext) extends Dal {
  implicit val exec = executionContext
  override type T[D] = Future[D]
  implicit val monadT = implicitly[Monad[Future]]

  def execute[D](operations: T[D]): Future[D]                   = operations
  def execute[D](operations: OptionT[T, D]): OptionT[Future, D] = OptionT(operations.run)
  def execute[D](operations: ListT[T, D]): ListT[Future, D]     = ListT(operations.run)
  def noAction[V](v: V): T[V]                                   = Future.successful(v)
  def customAction[R](action: => Future[R]): T[R]               = action
}

class SlickDal(database: Database, executionContext: ExecutionContext) extends Dal {
  import database.profile.api._
  implicit val exec = executionContext
  override type T[D] = slick.dbio.DBIO[D]

  implicit protected class QueryOps[+E, U](query: Query[E, U, Seq]) {
    def resultT: ListT[T, U] = ListT[T, U](query.result.map(_.toList))
  }

  override implicit val monadT: Monad[T] = new Monad[T] {
    override def point[A](a: => A): T[A]                  = DBIO.successful(a)
    override def bind[A, B](fa: T[A])(f: A => T[B]): T[B] = fa.flatMap(f)
  }

  override def execute[D](readOperations: T[D]): Future[D] = {
    database.database.run(readOperations.transactionally)
  }

  override def execute[D](readOperations: OptionT[T, D]): OptionT[Future, D] = {
    OptionT(database.database.run(readOperations.run.transactionally))
  }

  override def execute[D](readOperations: ListT[T, D]): ListT[Future, D] = {
    ListT(database.database.run(readOperations.run.transactionally))
  }

  override def noAction[V](v: V): T[V]                     = DBIO.successful(v)
  override def customAction[R](action: => Future[R]): T[R] = DBIO.from(action)
}
