// One function per route the interface calls (contracts/http-api.md). Each answers the service's
// record as it is; a refusal is a BackendError with the service's own message.
import { backend, post, readEvents, type StreamEvent } from "./client.ts";
import type * as T from "./types.ts";

const id = encodeURIComponent;

// requests, journey, facets
export const listRequests = () => backend<T.RequestRow[]>("/requests");
export const submitRequest = (text: string, submittedBy: string) =>
  post<T.Created>("/requests", { text, submittedBy });
export const getRequest = (r: string) => backend<T.RequestView>(`/requests/${id(r)}`);
export const getJourney = (r: string) => backend<T.Journey>(`/requests/${id(r)}/journey`);
export const watchJourney = (r: string, signal: AbortSignal, onJourney: (j: T.Journey) => void) =>
  readEvents(`/requests/${id(r)}/journey:watch`, { signal }, (e: StreamEvent) => {
    if (e.event === "journey") onJourney(JSON.parse(e.data) as T.Journey);
  });
export const getFacets = (r: string) => backend<T.Facet[]>(`/requests/${id(r)}/facets`);
export const getProposal = (r: string) => backend<T.ProposalStatus>(`/requests/${id(r)}/proposal`);
export const reviewFacet = (f: string, body: { action: string; reviewer: string; rationale: string; statement?: string }) =>
  post<T.Created>(`/facets/${id(f)}/review`, body);
export const assumeFacet = (f: string, body: { value: string; reviewer: string; rationale: string }) =>
  post<T.Created>(`/facets/${id(f)}/assume`, body);
export const confirmFacets = (r: string, reviewer: string) =>
  post<unknown>(`/requests/${id(r)}/facets:confirmed`, { reviewer });
export const rerunRoundTrip = (r: string) => post<unknown>(`/requests/${id(r)}/round-trip:rerun`);
export const getWorkflow = (kind: string, w: string) => backend<T.WorkflowView>(`/workflows/${id(kind)}/${id(w)}`);
export const getReviewers = () => backend<T.Reviewer[]>("/reviewers");

// questions and search
export const listQuestions = (r: string) => backend<T.ScientificQuestion[]>(`/requests/${id(r)}/questions`);
export const getQuestion = (q: string) => backend<T.QuestionView>(`/questions/${id(q)}`);
export const getPlan = (p: string) => backend<T.SearchPlan>(`/search-plans/${id(p)}`);
export const planQuestion = (q: string) => post<unknown>(`/questions/${id(q)}/plan`);
export const listExecutions = (q: string) => backend<unknown[]>(`/questions/${id(q)}/executions`);
export const getExecution = (e: string) => backend<T.SearchExecution>(`/search-executions/${id(e)}`);
export const executePlan = (p: string, sources: string[] = []) => post<unknown>(`/search-plans/${id(p)}/execute`, { sources });
export const rerunExecution = (e: string) => post<T.RerunView>(`/search-executions/${id(e)}/rerun`);
export const searchGap = (g: string) => post<unknown>(`/gaps/${id(g)}/search`);
export const getGaps = (q: string) => backend<T.KnowledgeGap[]>(`/questions/${id(q)}/gaps`);

// sources
export const listSources = () => backend<T.SourceRow[]>("/sources");
export const getSource = (s: string) => backend<T.SourceDocument>(`/sources/${id(s)}`);
export const sourceRecords = (s: string) => backend<T.RecordRow[]>(`/sources/${id(s)}/records`);
export const getSection = (s: string, section: string) => backend<unknown>(`/sources/${id(s)}/sections/${id(section)}`);
export const ingestSource = (adapter: string, identifier: string, extract = true) =>
  post<T.IngestStarted>("/sources:ingest", { adapter, identifier, extract });
export const ingestInternal = (manifestPath: string) => post<T.IngestStarted[]>("/sources:ingest-internal", { manifestPath });
export const setSourceStatus = (s: string, status: string, reason: string, by: string) =>
  post<T.SourceDocument>(`/sources/${id(s)}/status`, { status, reason, by });
export const extractSource = (s: string, questionIds: string[] = []) => post<unknown>(`/sources/${id(s)}/extract`, { questionIds });

// findings, claims, assessments, sufficiency
export const getFinding = (f: string) => backend<T.RecordView>(`/findings/${id(f)}`);
export const getClaim = (c: string) => backend<T.RecordView>(`/claims/${id(c)}`);
export const chunkContext = (chunk: string) => backend<unknown>(`/chunks/${id(chunk)}/context`);
export const reviewRecord = (kind: "finding" | "claim" | "assessment", r: string, body: { action: string; reviewer: string; rationale: string }) =>
  post<unknown>(`/${kind === "finding" ? "findings" : kind === "claim" ? "claims" : "assessments"}/${id(r)}/review`, body);
export const injectFault = (kind: string, recordId: string, mutation: string) =>
  post<unknown>("/faults:inject", { target: { kind, id: recordId }, mutation });
export const getAssessment = (a: string) => backend<T.EvidenceAssessment>(`/assessments/${id(a)}`);
export const assessRecords = (q: string, recordIds: string[]) => post<unknown>(`/questions/${id(q)}/assess`, { recordIds });
export const judgeSufficiency = (q: string, intendedDecision: string) =>
  post<unknown>(`/questions/${id(q)}/sufficiency`, { intendedDecision });

// packages, candidates, decision, design, impact, provenance, queue
export const getPackage = (p: string) => backend<T.PackageView>(`/packages/${id(p)}`);
export const buildPackages = (r: string) => post<unknown>(`/requests/${id(r)}/packages`);
export const getApproaches = (r: string) => backend<T.ApproachesView>(`/requests/${id(r)}/approaches`);
export const decide = (
  r: string,
  body: { selectedApproachId: string; rejected: { approachId: string; reason: string }[]; decidedBy: string; rationale: string },
) => post<T.DecisionRecord>(`/requests/${id(r)}/decide`, body);
export const getDesign = (d: string) => backend<T.DesignState>(`/designs/${id(d)}`);
export const listDesigns = () => backend<unknown[]>("/designs");
export const approveDesign = (
  d: string,
  body: { version: number; decision: string; reviewer: string; rationale: string; requestedChanges?: string[] },
) => post<unknown>(`/designs/${id(d)}/approval`, body);
export const submitDesign = (d: string, version: number, by: string) => post<unknown>(`/designs/${id(d)}/submit`, { version, by });
export const getImpact = (ref: string) => backend<T.ImpactView>(`/impact?ref=${id(ref)}`);
export const getRun = (run: string) => backend<T.StageRun>(`/runs/${id(run)}`);
export const reviewQueue = () => backend<T.RecordRow[]>("/review-queue");

// the assistant and conversations
export const assistantStatus = () => backend<T.AssistantStatus>("/assistant");
export const getConversation = (c: string) => backend<T.Conversation>(`/conversations/${id(c)}`);
export const openConversation = (c: string, subject?: string) =>
  post<T.Conversation>(`/conversations/${id(c)}`, subject ? { subject } : {});
export const confirmProposal = (c: string, n: number) => post<T.Proposal>(`/conversations/${id(c)}/proposals/${n}:confirm`);
export const declineProposal = (c: string, n: number) => post<T.Proposal>(`/conversations/${id(c)}/proposals/${n}:decline`);

export interface TurnHandlers {
  token: (text: string) => void;
  proposal: (p: T.Proposal) => void;
  done: (d: T.TurnDone) => void;
  error: (reason: string) => void;
}

/** Puts a question and reads the answer as it is written. */
export const askTurn = (c: string, question: string, signal: AbortSignal, on: TurnHandlers) =>
  readEvents(
    `/conversations/${id(c)}/turns`,
    { method: "POST", body: JSON.stringify({ question }), signal, headers: { "Content-Type": "application/json" } },
    (e) => {
      const data = e.data === "" ? null : JSON.parse(e.data);
      // A plain `sse` route sends each chunk unnamed, as a JSON string.
      if (e.event === "token" || e.event === "message") on.token(typeof data === "string" ? data : String(data ?? ""));
      else if (e.event === "proposal") on.proposal({ status: "proposed", ...(data as Omit<T.Proposal, "status">) });
      else if (e.event === "done") on.done(data as T.TurnDone);
      else if (e.event === "error") on.error((data as { reason?: string })?.reason ?? "the answer failed");
    },
  );
