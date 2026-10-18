package com.thinkmorestupidless.experimentproposals

import com.thinkmorestupidless.experimentproposals.adapters.{
  Adapter,
  CrossrefAdapter,
  EuropePmcAdapter,
  FileAdapter,
  InternalAdapter,
  PubMedAdapter
}
import com.thinkmorestupidless.experimentproposals.api.{ApiEndpoint, HealthEndpoint, ReviewEndpoint}
import com.thinkmorestupidless.experimentproposals.application.agents.{
  DesignAgent,
  EvidenceAgent,
  RequestAgent,
  SearchAgent
}
import com.thinkmorestupidless.experimentproposals.application.consumers.{
  ClaimImpactTrigger,
  ClaimVerificationTrigger,
  FindingImpactTrigger,
  FindingVerificationTrigger,
  SourceImpactTrigger
}
import com.thinkmorestupidless.experimentproposals.application.entities.{
  ApproachEntity,
  DecisionEntity,
  DesignEntity,
  PackageEntity,
  AssessmentEntity,
  ClaimEntity,
  FacetEntity,
  FindingEntity,
  GapEntity,
  QuestionEntity,
  RequestEntity,
  SearchExecutionEntity,
  SearchPlanEntity,
  SourceEntity,
  StageRunEntity,
  SufficiencyEntity
}
import com.thinkmorestupidless.experimentproposals.application.views.{
  AssessmentRows,
  ChunkRows,
  ClaimRows,
  FacetRows,
  Dependents,
  DesignRows,
  ExecutionRows,
  FindingRows,
  MeasurementRows,
  QuestionRows,
  RequestRows,
  SourceRows,
  StageRows
}
import com.thinkmorestupidless.experimentproposals.application.workflows.{
  ImpactWorkflow,
  IngestionWorkflow,
  InvestigationWorkflow,
  QuestionInvestigationWorkflow,
  VerificationWorkflow
}
import com.thinkmorestupidless.experimentproposals.config.{Configured, Settings}
import com.thinkmorestupidless.experimentproposals.domain.provenance.Reviewers
import com.thinkmorestupidless.experimentproposals.replay.ModelProviders
import com.thinkmorestupidless.experimentproposals.retrieval.Embedder
import com.thinkmorestupidless.ankka.agent.{AgentRuntime, ModelProvider}
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime}

import java.nio.file.Path

/**
 * The whole service definition: the complete inventory of what it hosts (plan.md, the component
 * table). Registration is explicit, so a component left out fails here, not at its first request.
 */
object Main:

  /** Every component, in one place, so the tests start exactly what the service runs. */
  def descriptors: Seq[ComponentDescriptor] =
    Seq(
      StageRunEntity.descriptor,
      SourceEntity.descriptor,
      RequestEntity.descriptor,
      FacetEntity.descriptor,
      QuestionEntity.descriptor,
      FindingEntity.descriptor,
      ClaimEntity.descriptor,
      AssessmentEntity.descriptor,
      SufficiencyEntity.descriptor,
      GapEntity.descriptor,
      SearchPlanEntity.descriptor,
      SearchExecutionEntity.descriptor,
      PackageEntity.descriptor,
      ApproachEntity.descriptor,
      DecisionEntity.descriptor,
      DesignEntity.descriptor,
      ChunkRows.descriptor,
      MeasurementRows.descriptor,
      SourceRows.descriptor,
      StageRows.descriptor,
      RequestRows.descriptor,
      FacetRows.descriptor,
      QuestionRows.descriptor,
      FindingRows.descriptor,
      ClaimRows.descriptor,
      AssessmentRows.descriptor,
      ExecutionRows.descriptor,
      DesignRows.descriptor,
      Dependents.descriptor,
      FindingVerificationTrigger.descriptor,
      ClaimVerificationTrigger.descriptor,
      SourceImpactTrigger.descriptor,
      FindingImpactTrigger.descriptor,
      ClaimImpactTrigger.descriptor,
      IngestionWorkflow.descriptor,
      ImpactWorkflow.descriptor,
      InvestigationWorkflow.descriptor,
      QuestionInvestigationWorkflow.descriptor,
      VerificationWorkflow.descriptor,
      RequestAgent.descriptor,
      SearchAgent.descriptor,
      EvidenceAgent.descriptor,
      DesignAgent.descriptor
    ) ++ AgentRuntime.descriptors

  def endpoints(settings: Settings): Seq[EndpointClients => HttpEndpoint] =
    Seq(
      clients => ApiEndpoint(settings, clients.componentClient, clients.viewClient),
      clients => ReviewEndpoint(settings, clients.componentClient, clients.viewClient),
      _ => HealthEndpoint(settings)
    )

  /**
   * The five adapters (research.md R7); the external ones carry the contact `EDP_CONTACT_EMAIL`.
   */
  def adapters(env: Map[String, String]): Vector[Adapter] =
    val contact = env.get("EDP_CONTACT_EMAIL").map(_.trim).filter(_.nonEmpty)
    val ncbiKey = env.get("EDP_NCBI_API_KEY").map(_.trim).filter(_.nonEmpty)
    Vector(
      FileAdapter(Path.of(env.getOrElse("EDP_FIXTURES_DIR", "evals/fixtures/papers"))),
      InternalAdapter(Path.of(env.getOrElse("EDP_INTERNAL_DIR", "internal"))),
      EuropePmcAdapter(contact),
      PubMedAdapter(contact, ncbiKey),
      CrossrefAdapter(contact)
    )

  /** Sets the configured values entities and workflows read; a test fixture calls this too. */
  def configure(settings: Settings, reviewers: Reviewers, env: Map[String, String]): Unit =
    Configured(
      settings,
      reviewers,
      adapters(env),
      Embedder.forName(settings.embeddingModel, settings.embeddingCache)
    )

  def agentRuntime(settings: Settings, provider: ModelProvider): AgentRuntime =
    AgentRuntime.withDefaultModel(provider, settings.modelTimeout)

@main def run(): Unit =
  val env       = sys.env
  val settings  = Settings.fromEnv(env).fold(problem => sys.error(problem), identity)
  val reviewers = Reviewers.load(env).fold(problem => sys.error(problem), identity)
  Main.configure(settings, reviewers, env)

  val service = Ankka.service
    .registerAll(Main.descriptors)
    .withExtension(ProjectionRuntime())
    .withExtension(Main.agentRuntime(settings, ModelProviders.forTier(settings)))
    .withExtension(HttpServer.of(Main.endpoints(settings)*))
    .start()
  // Retrieval in a workflow step reads the chunk view through the started service's client.
  Configured.views = Some(service.viewClient)

  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await
    .result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
