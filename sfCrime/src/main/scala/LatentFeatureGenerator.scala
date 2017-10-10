import java.io.FileWriter
import java.time.LocalDate
import java.time.format.DateTimeFormatter

import cats.syntax.either._
import com.twitter.util.{TimerTask, Duration, JavaTimer}
import io.circe.Decoder
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._

import scala.collection.mutable
import scala.io.Source

/**
  * Created by kunal krishna on 5/2/2017.
  */

sealed trait SunSet_SunRiseEnum {
  def sunrise: Option[String]
  def sunset: Option[String]
}

case class SunSet_SunRise(sunrise: Option[String], sunset: Option[String]) extends SunSet_SunRiseEnum

case class SunSet_SunRise_Date(date: String, sunrise: Option[String], sunset: Option[String]) extends SunSet_SunRiseEnum

object SunSet_SunRise {
  implicit val decodeSunSetSunRise: Decoder[SunSet_SunRise] = Decoder.instance(c =>
  for {
    res <- c.downField("results").as[Map[String, String]]
  } yield SunSet_SunRise(res.get("sunrise"), res.get("sunset"))
  )
}

object LatentFeatureGenerator {

  val SUNRISE_SUNSET_API = "http://api.sunrise-sunset.org/json?lat=%f&lng=%f&date=%s"

  def main(args: Array[String]): Unit = {
    val sfLat = 37.7749d
    val sfLng = -122.4194d

    val minDate = LocalDate.of(2003, 1, 1)
    val maxDate = LocalDate.of(2003, 1, 3)

    val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    val injectUrlsQueue = mutable.Queue(
      minDate.toEpochDay.to(maxDate.toEpochDay).map(LocalDate.ofEpochDay).map {
        d =>
          val dateFormatted = d.format(dateFormatter)
          (dateFormatted, SUNRISE_SUNSET_API.format(sfLat, sfLng, dateFormatted))
      }: _*
    )

    require(injectUrlsQueue.nonEmpty, "the urls queue should not be empty")

    val fileWriter = new FileWriter("sfcrime/src/main/resources/sunrise_sunset.json")
    fileWriter.write("[\n")

    val timer = new JavaTimer(isDaemon = false)

    lazy val task: TimerTask = timer.schedule(Duration.fromSeconds(5)) {
      val (date, url) = injectUrlsQueue.dequeue()
      val sunRiseSunSet = decode[SunSet_SunRise](Source.fromURL(url).mkString).map(s => SunSet_SunRise_Date(date, s.sunrise, s.sunset))

      sunRiseSunSet match {
        case Right(s) =>
          val json = s.asJson.spaces2
          if (injectUrlsQueue.isEmpty) {
            task.cancel()
            timer.stop()
            fileWriter.write(s"$json\n")
            fileWriter.write("]")
            fileWriter.close()
          } else {
            fileWriter.write(s"$json,\n")
          }
        case Left(e) => print("couldn't retrieve sunset/sunrise data" + e.toString)
      }
    }

    require(task != null)
  }

}
