import type { Metadata } from "next";
import CampaignGoals from "./CampaignGoals";

export const metadata: Metadata = {
  title:"Metas de produtos e campanhas | AdPilot",
  description:"Associação manual e auditável de objetivos econômicos de produtos a campanhas Meta.",
};

export default function PlanningPage() {
  return <CampaignGoals/>;
}
