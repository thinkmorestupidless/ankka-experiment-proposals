// The records the cards show, as the service's JSON writes them: the fields are the service's own
// names, enums are their case names, and a field equal to its default (an empty list, no value, a
// zero) may be absent, so those are optional here. One interface per record.

export type Instant = string;

export interface Ref {
  kind: string;
  id: string;
  version: number;
}

export interface ReviewAction {
  reviewer: string;
  action: string;
  at: Instant;
  rationale: string;
  before?: string | null;
  after?: string | null;
}

export interface ReviewFlag {
  changedRef: Ref;
  reason: string;
  at: Instant;
}

export interface Defect {
  kind: string;
  severity: string;
  detail: string;
  retryable: boolean;
}

export interface Reviewer {
  name: string;
  role: string;
  standIn: boolean;
}

// ── requests and facets ─────────────────────────────────────────────────────

export interface RequestRow {
  id: string;
  submittedAt: string;
  status: string;
  facets: number;
  questions: number;
  roundTripPassed?: boolean | null;
  text?: string;
  submittedBy?: string;
}

export interface FacetRef {
  id: string;
  name: string;
  importance: string;
}

export interface Issue {
  description: string;
  facetName?: string | null;
  span?: string | null;
}

export interface RoundTripResult {
  check: {
    reconstruction: string;
    omissions: Issue[];
    unsupportedAdditions: Issue[];
    contradictions: Issue[];
    ambiguities: Issue[];
  };
  passed: boolean;
  facetSetVersion: number;
  run: number;
  promptVersion: string;
  at: Instant;
}

export interface CustomerRequest {
  id: string;
  registered?: boolean;
  text?: string;
  submittedBy?: string;
  submittedAt?: Instant | null;
  contentHash?: string;
  facets?: FacetRef[];
  facetSetVersion?: number;
  questionIds?: string[];
  roundTrip?: RoundTripResult | null;
  roundTripRuns?: number;
  confirmedBy?: string | null;
  status?: string;
}

export interface Span {
  start: number;
  end: number;
  quotation: string;
}

export interface Facet {
  id: string;
  registered?: boolean;
  requestId?: string;
  name?: string;
  statement?: string;
  origin?: "explicit" | "inferred" | "domain_derived" | "human_confirmed";
  importance?: "mandatory" | "preferred" | "exploratory";
  status?: "proposed" | "confirmed" | "ambiguous" | "unresolved" | "rejected" | "assumed";
  requestSpans?: Span[];
  dependencies?: string[];
  reviews?: ReviewAction[];
  history?: { statement: string; origin: string; status: string; at: Instant }[];
  assumedValue?: string | null;
  flags?: ReviewFlag[];
  version?: number;
}

export interface ProposalStatus {
  requestId: string;
  confirmed: string[];
  assumedWithApproval: string[];
  unresolvedAndFlagged: string[];
  stillProposed: string[];
  assumedValues: Record<string, string>;
}

export interface WorkflowView {
  kind: string;
  id: string;
  status: string;
  pendingStep?: string | null;
  failure?: string | null;
  isTerminal: boolean;
  sourceId?: string | null;
  state: string;
}

export interface RequestView {
  request: CustomerRequest;
  facets: Facet[];
  questions: ScientificQuestion[];
  workflow: WorkflowView;
}

export interface Created {
  id: string;
  version: number;
  workflowId?: string | null;
}

// ── questions and search ───────────────────────────────────────────────────

export interface ScientificQuestion {
  id: string;
  registered?: boolean;
  requestId?: string;
  question?: string;
  facetRefs?: Ref[];
  facetNames?: string[];
  evidenceNeeded?: string[];
  answerFields?: string[];
  sufficiencyCriteria?: string[];
  status?: string;
  missingInformation?: string[];
  planId?: string | null;
  advancedBy?: Ref | null;
  flags?: ReviewFlag[];
  version?: number;
}

export interface Query {
  kind: "lexical" | "semantic";
  text: string;
  expansionOf?: string | null;
}

export interface SearchPlan {
  id: string;
  registered?: boolean;
  questionRef?: Ref | null;
  queries?: Query[];
  targetSources?: string[];
  filters?: Record<string, string>;
  parameters?: Record<string, string>;
  stoppingCriterion?: string;
  escalation?: string;
  expansions?: string[];
  executionIds?: string[];
  plannedBy?: string;
  version?: number;
}

export interface Hit {
  rank: number;
  score: number;
  sourceId: string;
  sourceVersion: number;
  chunkId?: string | null;
  sectionId?: string | null;
  lexicalRank?: number | null;
  semanticRank?: number | null;
  identifier?: string | null;
  title?: string | null;
}

export interface Exclusion {
  sourceId: string;
  sourceVersion: number;
  chunkId?: string | null;
  reason: string;
  ruleId: string;
  action: "excluded" | "downgraded";
}

export interface SearchExecution {
  id: string;
  registered?: boolean;
  planRef?: Ref | null;
  questionId?: string;
  query?: Query | null;
  fused?: boolean;
  source?: string;
  executedAt?: Instant | null;
  parameters?: Record<string, string>;
  snapshot?: Ref[];
  results?: Hit[];
  eligibleResults?: Hit[];
  exclusions?: Exclusion[];
  eligibilityApplied?: boolean;
  coverageGap?: boolean;
  error?: string | null;
  rerunOf?: string | null;
  gapId?: string | null;
  version?: number;
}

export interface RerunView {
  execution: SearchExecution;
  matches: boolean;
}

export interface KnowledgeGap {
  id: string;
  registered?: boolean;
  questionRef?: Ref | null;
  description?: string;
  kind?: string;
  escalation?: string;
  status?: string;
  supplementalExecutionIds?: string[];
  version?: number;
}

export interface QuestionView {
  question: ScientificQuestion;
  investigation?: Record<string, unknown> | null;
  plan?: SearchPlan | null;
  executions: SearchExecution[];
  assessments: EvidenceAssessment[];
  sufficiency: EvidenceSufficiency[];
  gaps: KnowledgeGap[];
}

// ── sources ────────────────────────────────────────────────────────────────

export interface SourceRow {
  id: string;
  identifiers: Record<string, string>;
  title: string;
  kind: string;
  status: string;
  latestVersion: number;
  eligible?: boolean | null;
  quarantined: boolean;
  retention: string;
}

export interface Section {
  id: string;
  parentId?: string | null;
  ordinal: number;
  kind: string;
  title: string;
  text: string;
  relatedSectionIds?: string[];
  parseConfidence?: string;
  tableResolved?: boolean;
}

export interface SourceVersion {
  version: number;
  contentHash: string;
  retrievedAt: Instant;
  sourceVersion?: string | null;
  retention: string;
  content?: string | null;
  parseVersion?: string | null;
  completeness?: string | null;
  issues?: { kind: string; location: string; detail: string }[];
  sections?: Section[];
  measurementIds?: string[];
  extractions?: { promptVersion: string; recordIds: string[]; at: Instant }[];
  quarantined?: boolean;
  quarantineReason?: string | null;
}

export interface SourceDocument {
  id: string;
  registered?: boolean;
  identifiers?: Record<string, string>;
  title?: string;
  authors?: string[];
  publishedAt?: string | null;
  venue?: string | null;
  sourceUrl?: string | null;
  licence?: string | null;
  kind?: string;
  abstractText?: string | null;
  status?: "active" | "corrected" | "retracted" | "superseded" | "restricted";
  statusCheckedAt?: Instant | null;
  versions?: SourceVersion[];
  eligibility?: {
    eligible: boolean;
    reasons: { ruleId: string; detail: string }[];
    assessedAt: Instant;
    assessedBy: string;
    ruleSetVersion: string;
  } | null;
  ingestions?: string[];
}

export interface IngestStarted {
  workflowId: string;
  sourceId?: string | null;
  status: string;
}

// ── evidence records ───────────────────────────────────────────────────────

export interface SourceSpan {
  sourceId: string;
  sourceVersion: number;
  sectionId: string;
  startOffset: number;
  endOffset: number;
  quotation: string;
  chunkId?: string | null;
}

export interface SpanView {
  span: SourceSpan;
  resolves: boolean;
  text?: string | null;
}

export interface FindingContent {
  species?: string | null;
  cellType?: string | null;
  experimentalModel?: string | null;
  conditions: string[];
  observedEndpoint: string;
  reportedOutcome: string;
  outcomeDirection: string;
  qualifications: string[];
  limitations: string[];
  authorInterpretation?: string | null;
  extractorUncertainty: string;
  missingValues: Record<string, string>;
}

export interface ClaimContent {
  proposition: string;
  kind: string;
  findingRefs: Ref[];
  derivedFromClaimRefs: Ref[];
  qualifications: string[];
  speciesScope?: string | null;
  endpointScope?: string | null;
}

/** The content of a record: a finding's or a claim's, told apart by `type`. */
export type RecordContent =
  | { type: "OfFinding"; finding: FindingContent }
  | { type: "OfClaim"; claim: ClaimContent };

export interface VerificationCheck {
  passed: boolean;
  evidenceQuote: string;
  note: string;
}

export interface VerificationRecord {
  attempt: number;
  label: "entailed" | "contradicted" | "insufficiently_supported" | "ambiguous";
  checks: Record<string, VerificationCheck>;
  contextSectionIds: string[];
  promptVersion: string;
  model: string;
  rationale: string;
  verifiedAt: Instant;
}

export interface Attempt {
  n: number;
  content: RecordContent;
  spans: SourceSpan[];
  generatedBy: string;
  triggeredBy?: Defect | null;
  verification?: VerificationRecord | null;
  outcome?: string | null;
  defect?: Defect | null;
  at: Instant;
}

export interface EvidenceRecord {
  id: string;
  registered?: boolean;
  kind?: string;
  sourceRef?: Ref | null;
  content?: RecordContent | null;
  spans?: SourceSpan[];
  generatedBy?: string;
  status?: "proposed" | "verified" | "rejected" | "requires_review" | "quarantined";
  attempts?: Attempt[];
  defect?: Defect | null;
  reviews?: ReviewAction[];
  dependsOn?: Ref[];
  flags?: ReviewFlag[];
  testedByDesignRef?: Ref | null;
  version?: number;
}

/** A finding or a claim, with each span beside the passage it resolves to. */
export interface RecordView {
  record: EvidenceRecord;
  spans: SpanView[];
}

export interface RecordRow {
  id: string;
  sourceId: string;
  kind: string;
  status: string;
  endpoint: string;
  species: string;
  outcome: string;
  proposition: string;
  generatedBy: string;
  attempts: number;
  flagged: boolean;
}

export interface EvidenceAssessment {
  id: string;
  registered?: boolean;
  questionRef?: Ref | null;
  recordRef?: Ref | null;
  relationship?: "supports" | "contradicts" | "qualifies" | "background" | "not_applicable";
  applicability?: "direct" | "indirect" | "unknown";
  dimensions?: Record<string, string>;
  rationale?: string;
  limitations?: string[];
  assessor?: string;
  reviewStatus?: string;
  ruleResults?: { ruleId: string; outcome: string; detail: string }[];
  reviews?: ReviewAction[];
  dependsOn?: Ref[];
  flags?: ReviewFlag[];
  version?: number;
}

export interface EvidenceSufficiency {
  id: string;
  registered?: boolean;
  questionRef?: Ref | null;
  intendedDecision?: string;
  proposition?: string;
  status?: string;
  assumptions?: string[];
  assessmentRefs?: Ref[];
  criticalGapIds?: string[];
  coverageJudgement?: string;
  rationale?: string;
  escalation?: string;
  judgedBy?: string;
  dependsOn?: Ref[];
  flags?: ReviewFlag[];
  version?: number;
}

// ── packages, candidates, decision, design ─────────────────────────────────

export interface EvidencePackage {
  id: string;
  registered?: boolean;
  questionRefs?: Ref[];
  requestRef?: Ref | null;
  facetRefs?: Ref[];
  assessmentRefs?: Ref[];
  recordRefs?: Ref[];
  includedSpans?: SourceSpan[];
  excluded?: { ref: Ref; reason: string }[];
  contradictoryAssessmentRefs?: Ref[];
  resourceConstraints?: string[];
  openQuestionIds?: string[];
  gapIds?: string[];
  checks?: { name: string; passed: boolean; detail: string }[];
  ruleSetVersion?: string | null;
  qualityStatus?: string;
  trims?: { ref: Ref; reason: string; critical?: boolean }[];
  builtBy?: string;
  flags?: ReviewFlag[];
  version?: number;
}

export interface PackageView {
  package: EvidencePackage;
  passages: SpanView[];
}

export interface ExperimentalApproach {
  id: string;
  registered?: boolean;
  title?: string;
  hypothesis?: string;
  rationale?: string;
  biologicalModel?: string;
  missingEvidence?: string[];
  modelLimitations?: string[];
  endpoints?: string[];
  measurements?: string[];
  controls?: string[];
  resources?: string[];
  safetyRegulatory?: string[];
  assumptions?: { statement: string; evidenceStatus: string }[];
  uncertainties?: string[];
  failureModes?: string[];
  possibleResults?: { result: string; informationGained: string }[];
  prerequisites?: string[];
  status?: string;
  version?: number;
  [field: string]: unknown;
}

export interface Comparison {
  id: string;
  approachRefs: Ref[];
  criteria: { name: string; perApproach: Record<string, { rating: string; rationale: string }> }[];
  comparedBy: string;
  comparedAt: Instant;
}

export interface DecisionRecord {
  id: string;
  registered?: boolean;
  requestRef?: Ref | null;
  comparison?: Comparison | null;
  selectedApproachRef?: Ref | null;
  rejected?: { approachRef: Ref; reason: string }[];
  decidedBy?: string;
  decidedAt?: Instant | null;
  rationale?: string;
  version?: number;
}

export interface ApproachesView {
  approaches: ExperimentalApproach[];
  comparison?: Comparison | null;
  decision?: DecisionRecord | null;
}

export interface Grounded {
  text: string;
  claimRefs: Ref[];
  spans: SourceSpan[];
}

export interface DesignSections {
  questionAndScope: string;
  background: Grounded[];
  hypothesis: string;
  modelAndPrerequisites: string;
  variablesControlsComparators: string;
  endpointsAndMeasurementPlan: string;
  successFailureInconclusiveCriteria: string;
  resourcesTimelineDependencies: string;
  limitations: string;
  alternatives: string;
  outcomesAndDecisionPoints: string;
  evidenceManifest: { claimRefs: Ref[]; spans: SourceSpan[]; approvalIds: string[] };
}

export interface Approval {
  version: number;
  decision: "approved" | "rejected" | "changes_requested";
  reviewer: string;
  at: Instant;
  rationale: string;
  requestedChanges?: string[];
}

export interface DesignVersion {
  version: number;
  approachRef: Ref;
  decisionRef: Ref;
  sections: DesignSections;
  status: "draft" | "under_review" | "approved" | "rejected" | "changes_requested" | "superseded";
  checks: { name: string; passed: boolean; detail: string }[];
  ruleSetVersion: string;
  generatedAt: Instant;
  generatedBy: string;
  changesApplied?: string[];
}

export interface DesignFlag {
  changedRef: Ref;
  version: number;
  reason: string;
  at: Instant;
}

export interface DesignState {
  id: string;
  registered?: boolean;
  requestRef?: Ref | null;
  versions?: DesignVersion[];
  approvals?: Approval[];
  flags?: DesignFlag[];
}

export interface DependentView {
  kind: string;
  id: string;
  version: number;
  flagged: boolean;
  depth: number;
}

export interface ImpactView {
  ref: string;
  dependents: DependentView[];
  allFlagged: boolean;
}

// ── provenance ─────────────────────────────────────────────────────────────

export interface StageExecution {
  stage: string;
  stageVersion: number;
  attempt: number;
  inputRefs: Ref[];
  inputHash: string;
  outputRef?: Ref | null;
  validations: { gate: string; passed: boolean; defects: Defect[]; ruleVersions: Record<string, number>; runAt: Instant }[];
  modelCalls: {
    promptVersion: string;
    model: string;
    effort: string;
    inputTokens: number;
    outputTokens: number;
    latencyMs: number;
    refused: boolean;
  }[];
  status: string;
  defect?: Defect | null;
  startedAt: Instant;
  endedAt?: Instant | null;
}

export interface StageRun {
  runId: string;
  workflow: string;
  stages: StageExecution[];
}

// ── the journey (contracts/journey.md) ─────────────────────────────────────

export type NodeState = "unreached" | "reached" | "current" | "paused" | "failed";

export interface StageRunRef {
  runId: string;
  stage: string;
  attempt: number;
  status: string;
  startedAt: string;
  endedAt?: string | null;
  defect?: string | null;
  actor?: string | null;
}

export interface StageNode {
  stage: string;
  state: NodeState;
  detail?: string | null;
  runs?: StageRunRef[];
}

export interface QuestionLane {
  questionId: string;
  text: string;
  nodes: StageNode[];
}

export interface Journey {
  requestId: string;
  spine: StageNode[];
  questions: QuestionLane[];
}

// ── the conversation (contracts/assistant.md, http-api.md) ─────────────────

export type CardKind =
  | "request"
  | "facets"
  | "question"
  | "search-plan"
  | "search-execution"
  | "source"
  | "finding"
  | "claim"
  | "assessment"
  | "sufficiency"
  | "package"
  | "candidates"
  | "decision"
  | "design"
  | "impact"
  | "stage-run";

export const cardKinds: readonly CardKind[] = [
  "request",
  "facets",
  "question",
  "search-plan",
  "search-execution",
  "source",
  "finding",
  "claim",
  "assessment",
  "sufficiency",
  "package",
  "candidates",
  "decision",
  "design",
  "impact",
  "stage-run",
];

export interface Reference {
  kind: CardKind | string;
  id: string;
  version?: number | null;
  resolved?: boolean;
}

export type DecisionKind = "facet-review" | "facets-confirmed" | "record-review" | "decide" | "approval";

export interface Prefill {
  decision: DecisionKind | string;
  target: string;
  fields: Record<string, string>;
}

export interface Turn {
  n: number;
  question: string;
  answer: string;
  references?: Reference[];
  prefill?: Prefill | null;
  at: Instant;
}

export interface Proposal {
  n: number;
  action: string;
  arguments: Record<string, string>;
  status: "proposed" | "performed" | "declined" | string;
  result?: Reference | null;
  at?: Instant;
}

export interface Conversation {
  id?: string;
  subject?: string | null;
  budget: number;
  used: number;
  turns?: Turn[];
  proposals?: Proposal[];
}

export interface AssistantStatus {
  available: boolean;
  reason?: string;
  budget?: number;
}

export interface TurnDone {
  n: number;
  references: Reference[];
  prefill?: Prefill | null;
  used: number;
  budget: number;
}
