package chaincash.offchain

import scalaj.http.{Http, HttpOptions}

trait HttpUtils {
  val ApiKey = "hello" //todo: externalize
  def getJsonAsString(url: String): String = {
    Http(s"$url")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .header("Charset", "UTF-8")
      .header("api_key", ApiKey)
      .option(HttpOptions.readTimeout(10000))
      .asString
      .body
  }

  def postString(url: String, data: String): String = {
    Http(s"$url")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .header("Charset", "UTF-8")
      .header("api_key", ApiKey)
      .option(HttpOptions.readTimeout(10000))
      .postData(data)
      .asString
      .body
  }

}