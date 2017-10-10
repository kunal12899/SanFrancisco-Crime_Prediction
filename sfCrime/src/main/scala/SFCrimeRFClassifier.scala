/**
  * Created by Rahul Aravind/kunal krishna on 5/3/2017.
  */



import java.time.LocalTime
import java.time.format.DateTimeFormatter

import cats.syntax.either._
import com.esri.core.geometry._
import io.circe.Decoder
import io.circe.parser.decode
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.ml.classification._
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator
import org.apache.spark.ml.feature.{IndexToString, StringIndexer, VectorAssembler}
import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, SparkSession}

import scala.io.Source


object SFCrimeRFClassifier {

  // loading
  def loadData(trainData: String, sparkSession: SparkSession): (DataFrame, DataFrame) = {
    val schema = Array(
      StructField("Id", LongType),
      StructField("Dates", TimestampType),
      StructField("Category", StringType),
      StructField("Descript", StringType),
      StructField("DayOfWeek", StringType),
      StructField("PdDistrict", StringType),
      StructField("Resolution", StringType),
      StructField("Address", StringType),
      StructField("X", DoubleType),
      StructField("Y", DoubleType)
    )

    val DataSchema = StructType(schema.filterNot(_.name == "Id"))
    // val testingData = StructType(schema.filterNot { p => Seq("Category", "Descript", "Resolution") contains p.name })

    val datasetDF = sparkSession.read.format("csv").option("header", "true").schema(DataSchema).load(trainData)
    // val testDF = sparkSession.read.format("csv").option("header", true).schema(testingData).load(testData)

    val Array(trainDF, testDF) = datasetDF.randomSplit(Array(0.7, 0.3))

    (trainDF, testDF)
  }

  case class Neighborhood(name: String, polygon: Polygon)
  object Neighborhood {
    implicit val decodeNeighborhood: Decoder[Neighborhood] = Decoder.instance( c =>
      for {
        name <- c.downField("name").as[String]
        poly <- c.downField("polygon").as[String]
      } yield Neighborhood(name, getGeometryFromWKT[Polygon](poly))
    )
  }

  def getGeometryFromWKT[T <: Geometry](wkt: String): T = {
    //print("DEBUG wkt " + wkt + "\n")
    val wktImportFlags = WktImportFlags.wktImportDefaults
    val geometryType = Geometry.Type.Unknown
    val geo = OperatorImportFromWkt.local().execute(wktImportFlags, geometryType, wkt, null)
    geo.asInstanceOf[T]
  }

  def main(args: Array[String]): Unit = {

    System.setProperty("hadoop.home.dir", "D:\\spark")

    val trainData = "sfCrime/src/main/dataset/train_sample.csv"
    val sunsetData = "sfCrime/src/main/resources/sunrise_sunset.json"
    val weatherData = "sfCrime/src/main/resources/weather.json"
    val neighborhoodData = "sfCrime/src/main/resources/SF_Neighborhoods.json"
    val rfOutputFile = "sfCrime/src/main/resources/rf_output.csv"
    val dtOutputFile = "sfCrime/src/main/resources/dt_output.csv"
    val nbOutputFile = "sfCrime/src/main/resources/nb_output.csv"
    val lgOutputFile = "sfCrime/src/main/resources/lg_output.csv"

    val sparkSession = SparkSession.builder().config("spark.master", "local").config("spark.executor.memory", "8g").appName("SF Crime classification").getOrCreate()
    sparkSession.sparkContext.setLogLevel("WARN")

    // load the training and testing data

    print("Dataset Loader initiated......." + "\n")

    val (trainDF, testDF) = loadData(trainData, sparkSession)

    val sunset_sunrise_DF = {
      val rdd = sparkSession.sparkContext.wholeTextFiles(sunsetData).map(_._2)
      sparkSession.read.json(rdd)
    }

    val weatherDF = {
      val rdd = sparkSession.sparkContext.wholeTextFiles(weatherData).map(_._2)
      sparkSession.read.json(rdd)
    }

    // extracting features from data frames

    val timeFeatures = (df: DataFrame) => {
      def dateUdf = udf { (timestamp: String) =>
        val dateFormatter = DateTimeFormatter.ofPattern("YYYY-MM-dd HH:mm:ss")
        val datePattern = DateTimeFormatter.ofPattern("YYYY-MM-dd")
        val time = dateFormatter.parse(timestamp)
        datePattern.format(time)
      }
      df.withColumn("HourOfDay", hour(col("Dates")))
        .withColumn("Month", month(col("Dates")))
        .withColumn("Year", year(col("Dates")))
        .withColumn("Timestamp", to_utc_timestamp(col("Dates"), "PST"))
        .withColumn("Date", dateUdf(col("Timestamp")))
    }

    print("Time Features " + timeFeatures.toString())


    val weekendFeature = (df: DataFrame) => {
      def weekendUdf = udf { (day : String) =>
        day match {
          case _ @ ("Friday" | "Saturday" | "Sunday") => 1
          case _ => 0
        }
      }
      df.withColumn("Weekend", weekendUdf(col("DayOfWeek")))
    }

    val addressFeature = (df: DataFrame) => {
      def addressTypeUdf = udf { (address : String) =>
        if (address contains "/") "Intersection"
        else "street"
      }

      val streetRegex = """\d{1,4} Block of (.+)""".r
      val intersectionRegex = """(.+) / (.+)""".r

      def addressUdf = udf {(address: String) =>
        streetRegex findFirstIn address match {
          case Some(streetRegex(s)) => s
          case None => intersectionRegex findFirstIn address match {
            case Some(intersectionRegex(s1, s2)) => if (s1 < s2) s1 else s2
            case None => address
          }
        }
      }

      df.withColumn("AddressType", addressTypeUdf(col("Address")))
        .withColumn("Street", addressUdf(col("Address")))
    }


    val neighborhood = decode[Seq[Neighborhood]](Source.fromFile(neighborhoodData).getLines().mkString) match {
      case Right(r) => r
      case Left(e) =>
        print("Error parsing neighboorhood file " + e)
        Seq.empty[Neighborhood]
    }

    print("dataset loader completed......" + "\n")

    def dayNightFeatureEngineering(sunSetSunRiseDF: DataFrame) (df: DataFrame): DataFrame = {
      def dayNightUDF = udf {(timestamp: String, sunrise: String, sunset: String) => {
        val dateFormatter = DateTimeFormatter.ofPattern("YYYY-MM-dd HH:mm:ss")
        val timeFormatter = DateTimeFormatter.ofPattern("h:mm:ss a")
        val time = LocalTime.parse(timestamp, dateFormatter)
        val sunriseTime = LocalTime.parse(sunrise, timeFormatter)
        val sunsetTime = LocalTime.parse(sunset, timeFormatter)

        if (sunriseTime.compareTo(sunsetTime) > 0) {
          if (time.compareTo(sunsetTime) > 0 && time.compareTo(sunriseTime) < 0) {
            "Night"
          } else {
            "Day"
          }
        } else {
          if (time.compareTo(sunriseTime) > 0 && time.compareTo(sunsetTime) < 0) {
            "Day"
          } else {
            "Night"
          }
        }
      }}

      df.join(sunSetSunRiseDF, df("Date") === sunSetSunRiseDF("date"))
        .withColumn("DayOrNight", dayNightUDF(col("timestamp"), col("sunrise"), col("sunset")))
    }

    def weatherFeatureEngineering(weatherDataFrame: DataFrame)(df: DataFrame): DataFrame =
      df.join(weatherDataFrame, "Date")

    def contains(x: Geometry, y: Geometry): Boolean =
      OperatorContains.local().execute(x, y, SpatialReference.create(3426), null)

    def neighborhoodFeatureEngineering(neighborhoodSeq: Seq[Neighborhood])(df : DataFrame): DataFrame = {
      def neighborhoodUDF = udf {(lat : Double, lng : Double) =>
        val point = getGeometryFromWKT[Point](s"POINT($lat $lng)")
        neighborhoodSeq.filter(n => contains(n.polygon, point)).map(_.name).headOption match {
          case Some(n) => n
          case None => "SF"
        }
      }

      df.withColumn("Neighborhood", neighborhoodUDF(col("X"), col("Y")))
    }

    print("Feature engineering inititated" + "\n")

    val features = List(timeFeatures,
      weekendFeature,
      addressFeature,
      dayNightFeatureEngineering(sunset_sunrise_DF)(_),
      weatherFeatureEngineering(weatherDF)(_),
      neighborhoodFeatureEngineering(neighborhood)(_))

    print("Feature engineering completed" + "\n")



    val Array(enrichedTrainDF, enrichedTestDF) = Array(trainDF, testDF) map (features reduce (_ andThen _))

    //print("\nEnriched Train DF " + enrichedTrainDF.rdd.foreach(println))
    //print("\nEnriched Test DF " + enrichedTestDF.rdd.foreach(println))

    val colLabel = "Category"
    val colPredictedLabel = "PredictedCategory"
    val colFeatures = "Features"
    val numFeatures = Seq("X", "Y", "temperatureC")

    val categoryFeatures = Seq("DayOfWeek", "PdDistrict", "DayOrNight", "Weekend", "HourOfDay", "Month", "Year", "AddressType",
      "Street", "weather", "Neighborhood")

    val dataCombined = enrichedTrainDF.select((numFeatures ++ categoryFeatures).map(col): _*).
      union(enrichedTestDF.select((numFeatures ++ categoryFeatures).map(col): _*))

    print("\nData Combined " + dataCombined.rdd.count())

    dataCombined.cache()

    print("\nBuilding pipeline for the machine learning model.....\n")

    val featureIndex = categoryFeatures.map{ col =>
      new StringIndexer().setInputCol(col).setOutputCol(col + "Indexed").fit(dataCombined)
    }

    featureIndex.foreach(println)

    val classIndex = new StringIndexer().setInputCol(colLabel).setOutputCol(colLabel + "Indexed").fit(enrichedTrainDF.union(enrichedTestDF))

    val featureVector = new VectorAssembler().setInputCols((categoryFeatures.map(_ + "Indexed") ++ numFeatures).toArray).
      setOutputCol(colFeatures)

    val randomForestModel = new RandomForestClassifier().setLabelCol(colLabel + "Indexed").setFeaturesCol(colFeatures).
      setMaxDepth(4).setMaxBins(2089)

    val indexToString = new IndexToString().
      setInputCol("prediction").
      setOutputCol(colPredictedLabel).
      setLabels(classIndex.labels)

    val pipeline = new Pipeline().setStages((featureIndex :+ classIndex :+ featureVector :+ randomForestModel :+ indexToString).toArray)

    val modelEvaluator = new MulticlassClassificationEvaluator().setLabelCol(colLabel + "Indexed").setPredictionCol("prediction").setMetricName("accuracy")

    val parameterGrid = new ParamGridBuilder().build()

    val crossValidator = new CrossValidator().setEstimator(pipeline).setEvaluator(modelEvaluator).
      setEstimatorParamMaps(parameterGrid).setNumFolds(3)

    print("Machine Model pipelines built....." + "\n")

    print("Random Classifier Model training initiated..." + "\n")

    // model training initiation
    val cvModel = crossValidator.fit(enrichedTrainDF)

    print("Random Forest classifier training completed..." + "\n")

    // predicting the test dataframe

    print("Random Forest classifier classifying test data" + "\n")

    val predictTest = cvModel.transform(enrichedTestDF)

    val predictions = predictTest.select("Dates", "DayOfWeek", "PdDistrict", "Address",
      "X", "Y", "HourOfDay", "Month", "Year", "Weekend", "Street", "sunrise", "sunset", "DayOrNight", "weather", "Neighborhood", "PredictedCategory")

    predictions.printSchema()

    //checking the importance of each feature
    val featureImportance = cvModel.bestModel.asInstanceOf[PipelineModel].stages(categoryFeatures.size + 2).
      asInstanceOf[RandomForestClassificationModel].featureImportances

    featureVector.getInputCols.zip(featureImportance.toArray).foreach{
      case (feature, imp) => println(s"feature: $feature, importance: $imp")
    }

    val bestEstimatorParamMap = cvModel.getEstimatorParamMaps
      .zip(cvModel.avgMetrics)
      .maxBy(_._2)
      ._1

    println(bestEstimatorParamMap)


    val accuracy = modelEvaluator.evaluate(predictTest)

    print("\n Random Forest Accuracy " + accuracy + "\n")

    predictions.coalesce(3).write.format("csv").option("header", "true").save(rfOutputFile)
  }

}
