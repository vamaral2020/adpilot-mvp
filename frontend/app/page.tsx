import { headers } from "next/headers";

type Overview = { app: string; release: string; database: string; integrations: Record<string,string> };
type Campaign = { id?: string; name?: string; status?: string; effective_status?: string };
type CampaignPage = { data: Campaign[]; after: string | null };

async function authorizedFetch(path: string) {
  // Credentials kept server-side; never exposed in browser JS bundles.
  const u = process.env.ADPILOT_ADMIN_USERNAME ?? "";
  const p = process.env.ADPILOT_ADMIN_PASSWORD ?? "";
  const url = process.env.INTERNAL_API_URL ?? "http://localhost:18080";
  return fetch(url + path, {
    headers: { Authorization: "Basic " + Buffer.from(u + ":" + p).toString("base64") },
    cache: "no-store",
  });
}
async function getOverview(): Promise<Overview | null> {
  try { const r = await authorizedFetch("/api/v1/system/overview"); return r.ok ? await r.json() : null; }
  catch { return null; }
}
async function getCampaigns(): Promise<CampaignPage | null> {
  try { const r = await authorizedFetch("/api/v1/meta/campaigns?limit=15"); return r.ok ? await r.json() : null; }
  catch { return null; }
}
function Status({ state }: { state: string }) {
  return <span className={"pill " + (state === "CONNECTED" ? "ok" : "pending")}>
    {state === "CONNECTED" ? "Conectado" : state === "NOT_CONFIGURED" ? "Não configurado" : state}
  </span>;
}
export const dynamic = "force-dynamic";
export default async function Home() {
  await headers();
  const overview = await getOverview();
  const campaigns = await getCampaigns();
  const meta = overview?.integrations.META_ADS ?? "NOT_CONFIGURED";
  return (
    <main className="shell">
      <aside className="sidebar">
        <div className="brand"><span className="brandLogo">A</span><span>AdPilot</span></div>
        <div className="navLabel">CONTROLE</div>
        <div className="nav active">▦ <span>Visão geral</span></div>
        <div className="nav disabled">↗ <span>Campanhas Meta (somente leitura)</span></div>
        <div className="nav disabled">◈ <span>Recomendações IA</span></div>
        <div className="nav disabled">◫ <span>Produtos e pedidos</span></div>
        <div className="navLabel gapTop">CONFIGURAÇÕES</div>
        <div className="nav disabled">⚙ <span>Integrações</span></div>
        <div className="sideBottom"><span className="dot"/> Ambiente local<br/><small>Autenticado · Somente leitura</small></div>
      </aside>
      <section className="content">
        <header className="top"><span>Workspace pessoal</span><span className="version">MVP 0.2.0 · Meta read-only</span></header>
        <div className="heading"><div><div className="eyebrow">ADPILOT / PAINEL</div><h1>Visão geral</h1><p>Campanhas reais da Meta, sem operações de escrita.</p></div><span className="stage">PR 001</span></div>
        <div className="notice"><span className="noticeIcon">ⓘ</span><div><strong>Modo somente leitura</strong><p>O AdPilot nunca altera orçamentos, campanhas ou anúncios nesta etapa.</p></div></div>
        <div className="stats">
          <div className="stat"><span>Investimento em anúncios</span><strong>—</strong><small>Insights: disponível pela API</small></div>
          <div className="stat"><span>Receita atribuída</span><strong>—</strong><small>Aguardando conciliação</small></div>
          <div className="stat"><span>ROAS</span><strong>—</strong><small>Sem cálculo consolidado</small></div>
          <div className="stat"><span>Pedidos pagos</span><strong>—</strong><small>Aguardando Nuvemshop</small></div>
        </div>
        <div className="sectionTitle"><div><h2>Conexões do sistema</h2><p>Status interno e disponibilidade de campanhas</p></div></div>
        <div className="connections">
          <div className="connection"><div className="connectorIcon db">▤</div><div><strong>PostgreSQL</strong><small>Banco da aplicação</small></div><Status state={overview?.database ?? "OFFLINE"}/></div>
          <div className="connection"><div className="connectorIcon meta">∞</div><div><strong>Meta Ads</strong><small>{campaigns ? "Consulta de campanhas respondendo" : "Não validado / indisponível"}</small></div><Status state={campaigns ? "CONNECTED" : meta}/></div>
          <div className="connection"><div className="connectorIcon shop">▣</div><div><strong>Nuvemshop</strong><small>Produtos e pedidos</small></div><Status state={overview?.integrations.NUVEMSHOP ?? "NOT_CONFIGURED"}/></div>
        </div>
        <div className="panel">
          <h2>Campanhas Meta Ads</h2>
          {campaigns ? (
            campaigns.data.length ? <ol>{campaigns.data.map(c => <li key={c.id}>{c.name ?? c.id} · {c.effective_status ?? c.status ?? "—"}</li>)}</ol>
            : <p>Conta conectada. Nenhuma campanha retornada nesta consulta.</p>
          ) : <p>Configure META_AD_ACCOUNT_ID e META_ACCESS_TOKEN no .env local para consultar campanhas.</p>}
          {campaigns?.after && <p>Existem mais campanhas; utilize a API com o cursor de paginação.</p>}
        </div>
        <footer>AdPilot · Consulta Meta Ads autorizada · Ambiente de desenvolvimento</footer>
      </section>
    </main>
  );
}
