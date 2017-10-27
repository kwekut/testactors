import scala.concurrent.Future

import org.scalatestplus.play._

import play.api.mvc._
import play.api.test._
import play.api.test.Helpers._

class ExampleControllerSpec extends PlaySpec with Results {

  "Example Page#index" should {
    "should be valid" in {
      val controller = new WebSocketController()
      val result: Future[Result] = controller.socket("token").apply(FakeRequest())
      val bodyText: String = contentAsString(result)
      bodyText mustBe "ok"
    }
  }
}

  def index = Action { implicit req =>
    Ok(views.html.index("ok"))
  }
  def socket(token: String) = WebSocket.tryAcceptWithActor[JsValue, JsValue] { request =>
    implicit val req = Request(request, AnyContentAsText(token))
    authenticatorService.retrieve flatMap {
      case None => Future(Left(Forbidden))
      case Some(auth) => 
          userService.retrieve(auth.loginInfo).map {
              case Some(user) =>
                  Right(WebSocketActor.props(Some(user), Some(commActor), Some(prossActor), Some(sparkActor)) _.toOpton)
              case None => Left(Forbidden)
          }
           
    }
  }