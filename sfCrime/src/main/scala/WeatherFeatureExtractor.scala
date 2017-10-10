import java.io.FileWriter
import java.time.LocalDate
import java.time.format.DateTimeFormatter

import com.twitter.util.{JavaTimer, TimerTask, Duration}
import io.circe.generic.auto._
import io.circe.syntax._

import scala.collection.mutable
import scala.io.Source
import scala.util.{Failure, Success, Try}

/**
  * Created by RahulAravind on 5/3/2017.
  */

sealed trait WeatherEnum {
  def temp: Double
  def weather: String
}

case class Weather(temp: Double, weather: String) extends WeatherEnum

object Weather {
  def parseWeather(unparsed: String): Weather = {
    val split = unparsed.split(",")
    print(split.asJson + "\n")
    Weather(split(1).toDouble, split(11))
  }
}

case class WeatherDate(date: String, temp: Double, weather: String) extends WeatherEnum

object WeatherDate {
  def buildWeatherDate(date: String, weatherSeq: Seq[Weather]) : WeatherDate =
  WeatherDate(
    date,
    weatherSeq.map(_.temp).sum / weatherSeq.size,
    weatherSeq.map(_.weather).foldLeft(Map.empty[String, Int]) {
      (acc, word) => acc + (word -> (acc.getOrElse(word, 0) + 1))
    }.toSeq.sortBy(-_._2).head._1
  )
}

object WeatherFeatureExtractor {

  val WEATHER_API_URL_TEMPLATE = "https://www.wunderground.com/history/airport/KSFO/%d/%d/%d/DailyHistory.html?format=1"

  def main(args: Array[String]): Unit = {
    val minDate = LocalDate.of(2003, 1, 1)
    val maxDate = LocalDate.of(2003, 1, 3)

    val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    val injectUrlsQueue = mutable.Queue(
      minDate.toEpochDay.to(maxDate.toEpochDay).map(LocalDate.ofEpochDay).map{
        d =>
          val dateFormatted = d.format(dateFormatter)
          (dateFormatted, WEATHER_API_URL_TEMPLATE.format(d.getYear, d.getMonthValue, d.getDayOfMonth))
      }:_*
    )

    require(injectUrlsQueue.nonEmpty, " the urls queue should not be empty")

    val fileWriter = new FileWriter("sfCrime/src/main/resources/weather.json")
    fileWriter.write("[\n")
    val timer = new JavaTimer(isDaemon = false)

    lazy val task: TimerTask = timer.schedule(Duration.fromSeconds(5)) {
      val (date, url) = injectUrlsQueue.dequeue()
      Try(Source.fromURL(url).getLines().drop(2)) match {
        case Success(lines) =>
          val weatherSeq = lines.map(Weather.parseWeather).toSeq
          val weatherDateData = WeatherDate.buildWeatherDate(date, weatherSeq)
          val json = weatherDateData.asJson.spaces2

          if(injectUrlsQueue.isEmpty) {
            task.cancel()
            timer.stop()
            fileWriter.write(s"$json\n")
            fileWriter.write("]")
            fileWriter.close()
          } else {
            fileWriter.write(s"$json,\n")
          }
        case Failure(ex) => print("weather api call failed" + ex.toString)
      }
    }

    require(task != null)
  }

}
