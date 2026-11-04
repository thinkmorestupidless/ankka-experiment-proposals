// Each stage of the journey: its title and its line icon. The spine's eleven, then a lane's six.
import type { ComponentType, SVGProps } from "react";
import {
  BadgeCheck,
  ClipboardCheck,
  FileQuestion,
  FileText,
  Filter,
  Gavel,
  GitCompare,
  ListChecks,
  MessageSquareText,
  Package,
  Repeat,
  Scale,
  Search,
  ShieldAlert,
  ShieldCheck,
  Stamp,
  Tags,
} from "lucide-react";

export interface StageInfo {
  title: string;
  icon: ComponentType<SVGProps<SVGSVGElement>>;
}

export const stages: Record<string, StageInfo> = {
  request: { title: "request", icon: MessageSquareText },
  facets: { title: "facets", icon: Tags },
  "round-trip": { title: "round-trip check", icon: Repeat },
  "facet-review": { title: "facet review", icon: ClipboardCheck },
  questions: { title: "questions", icon: FileQuestion },
  packages: { title: "packages", icon: Package },
  candidates: { title: "candidates", icon: GitCompare },
  decision: { title: "decision", icon: Gavel },
  design: { title: "design", icon: FileText },
  approval: { title: "approval", icon: Stamp },
  impact: { title: "impact", icon: ShieldAlert },
  "search-plan": { title: "search plan", icon: ListChecks },
  executions: { title: "search", icon: Search },
  eligibility: { title: "eligibility", icon: Filter },
  verification: { title: "verification", icon: ShieldCheck },
  assessment: { title: "assessment", icon: BadgeCheck },
  sufficiency: { title: "sufficiency", icon: Scale },
};

export const stageTitle = (stage: string): string => stages[stage]?.title ?? stage;

/** The stage a title names ("search" is the lane's `executions`), for the breadcrumb and the steps. */
export function stageNamed(title: string): string {
  const found = Object.entries(stages).find(([, s]) => s.title === title);
  return found ? found[0] : title;
}
