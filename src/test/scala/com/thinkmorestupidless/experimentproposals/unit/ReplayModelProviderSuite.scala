package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.application.agents.Prompts
import com.thinkmorestupidless.experimentproposals.replay.*
import com.thinkmorestupidless.ankka.agent.*

import java.nio.file.Files
import scala.concurrent.Await
import scala.concurrent.duration.*

class ReplayModelProviderSuite extends munit.FunSuite:

  private def request(system: String, user: String) =
    ModelRequest(
      ModelSettings("m"),
      Some(system),
      Vector(ChatMessage.User(Vector(MessageContent.Text(user))))
    )

  test("a cassette is found by prompt version and case, and a refusal cassette refuses") {
    val dir   = Files.createTempDirectory("cassettes")
    val store = Cassettes(dir)
    store.write(Cassette("facets@v1", "rq-1", """{"facets":[]}""", recorded = false))
    store.write(Cassette("facets@v1", "rq-2", "", recorded = false, refusal = Some("declined")))
    val provider = new ReplayModelProvider(store)
    val answered = Await.result(
      provider.complete(
        request("# prompt_version: facets@v1\nrest", Prompts.userMessage("rq-1", "go"))
      ),
      5.seconds
    )
    assertEquals(answered.text, """{"facets":[]}""")
    val refused = Await.result(
      provider.complete(request("# prompt_version: facets@v1", Prompts.userMessage("rq-2", "go"))),
      5.seconds
    )
    assert(refused.isRefusal)
  }

  test("a missing cassette fails naming the key, and an unkeyed request fails naming why") {
    val provider = new ReplayModelProvider(Cassettes(Files.createTempDirectory("empty")))
    val missing = intercept[ModelCallFailed](
      Await.result(
        provider.complete(
          request("# prompt_version: facets@v1", Prompts.userMessage("rq-9", "go"))
        ),
        5.seconds
      )
    )
    assert(missing.getMessage.contains("facets@v1 / rq-9"), missing.getMessage)
    val unkeyed = intercept[ModelCallFailed](
      Await.result(provider.complete(request("no header", "no case")), 5.seconds)
    )
    assert(unkeyed.getMessage.contains("prompt_version"), unkeyed.getMessage)
  }

  test("the case is found in any user message, so context before the ask does not hide it") {
    val req = ModelRequest(
      ModelSettings("m"),
      Some("# prompt_version: verify@v1"),
      Vector(
        ChatMessage.User(Vector(MessageContent.Text("[S1] Methods\nWe cultured."))),
        ChatMessage.User(
          Vector(MessageContent.Text(Prompts.userMessage("src:doi:1:v1:fd1", "verify")))
        )
      )
    )
    assertEquals(CassetteKey.of(req).map(_.caseMarker), Right("src:doi:1:v1:fd1"))
    assertEquals(CassetteKey.slugOf("src:doi:10.1/abc:v1"), "src_doi_10.1_abc_v1")
  }

  test("the recording provider writes what the inner model answered") {
    val dir                                 = Files.createTempDirectory("rec")
    val inner                               = TestModelProvider().expectText("""{"ok":true}""")
    given scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    val recording                           = new RecordingModelProvider(inner, Cassettes(dir))
    Await.result(
      recording.complete(request("# prompt_version: plan@v1", Prompts.userMessage("q-1", "plan"))),
      5.seconds
    )
    val written = Cassettes(dir).read(CassetteKey("plan@v1", "q-1")).get
    assertEquals(written.reply, """{"ok":true}""")
    assert(written.recorded)
  }
