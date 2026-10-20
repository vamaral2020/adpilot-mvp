import Link from "next/link";
import {
  money, number, percentage, multiplier, presetPeriod, readableDate, resolvePeriod, finite,
  type MetaAccount, type CampaignPage, type Campaign, type InsightSummary,
  type InsightMetrics, type Period
} from "../lib/dashboard";

export const dynamic = "force-dynamic";

type Params = { since?: string | string[]; until?: string | string[] };
type Load<T> = { data?: T; error?: string };

async function readApi<T>(path: string): Promise<Load<T>> {
  const username = process.env.ADPILOT_ADMIN_USERNAME ?? "";
  const password = process.env.ADPILOT_ADMIN_PASSWORD ?? "";
  const origin = process.env.INTERNAL_API_URL ?? "http://localhost:18080";
  if (!username || !password) return { error: "Autenticação interna não configurada." };
  try {
    // Server-side only: credentials never reach client JavaScript.
    const response = await fetch(origin + path, {
      headers: { Authorization: "Basic " + Buffer.from(username + ":" + password).toString("base64") },
      cache: "no-store",
      signal: AbortSignal.timeout(45_000),
    });
    if (!response.ok) {
      const label = response.status === 503 ? "Serviço indisponível" :
        response.status === 502 ? "Erro da integração com a Meta" :
        response.status === 401 || response.status === 403 ? "Acesso negado pela API" :
        "Falha na consulta";
      return { error: label + " (HTTP " + response.status + ")." };
    }
    return { data: (await response.json()) as T };
  } catch {
    return { error: "Não foi possível consultar o backend do AdPilot." };
  }
}

const presetUrl = (period: Period) => "/?since=" + period.since + "&until=" + period.until;

function Icon({ name }: { name: "grid" | "activity" | "layers" | "settings" | "arrow" | "chart" }) {
  const path: Record<typeof name, React.ReactNode> = {
    grid: <><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></>,
    activity: <><path d="M3 17l6-6 4 4 8-8"/><path d="M15 7h6v6"/></>,
    layers: <><path d="m12 3 9 5-9 5-9-5 9-5Z"/><path d="m3 12 9 5 9-5M3 16l9 5 9-5"/></>,
    settings: <><circle cx="12" cy="12" r="3"/><path d="M12 2v3m0 14v3M4.9 4.9 7 7m10 10 2.1 2.1M2 12h3m14 0h3M4.9 19.1 7 17M17 7l2.1-2.1"/></>,
    arrow: <><path d="M4 16.5 10 10l4 4 6-7"/><path d="M15 7h5v5"/></>,
    chart: <><path d="M3 3v18h18"/><path d="m7 15 4-5 4 3 4-7"/></>,
  };
  return <svg width="18" height="18" viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {path[name]}
  </svg>;
}

function Sidebar() {
  return <aside className="sidebar">
    <div className="brand"><span className="brandLogo">A<span className="brandDot">.</span></span><span>AdPilot</span></div>
    <div className="sidebarSection">WORKSPACE</div>
    <nav aria-label="Menu principal" className="sideNav">
      <Link href="/" className="nav active"><Icon name="grid"/> Visão geral</Link>
      <Link href="#campanhas" className="nav"><Icon name="activity"/> Campanhas</Link>
      <span className="nav disabled" title="Disponível em etapa futura"><Icon name="chart"/> Recomendações IA <span className="coming">Em breve</span></span>
    </nav>
    <div className="sidebarSection sidebarSectionSecond">GESTÃO</div>
    <nav aria-label="Módulos planejados" className="sideNav">
      <span className="nav disabled"><Icon name="layers"/> Pedidos e produtos</span>
      <span className="nav disabled"><Icon name="settings"/> Integrações</span>
    </nav>
    <div className="sidebarFooter">
      <span className="liveDot" aria-hidden="true"/> Ambiente local
      <small>Acesso protegido · Somente leitura</small>
    </div>
  </aside>;
}

function Card({ label, value, hint, accent = false, icon }: {
  label: string; value: string; hint: string; accent?: boolean; icon: React.ReactNode;
}) {
  return <article className={"kpiCard" + (accent ? " kpiAccent" : "")}>
    <div className="kpiTop"><span>{label}</span><span className="kpiIcon">{icon}</span></div>
    <strong className="kpiValue">{value}</strong>
    <span className="kpiHint">{hint}</span>
  </article>;
}

function PeriodFilter({ period }: { period: Period }) {
  // Recompute on each server render so presets do not become stale overnight.
  const sevenDays = presetPeriod(7);
  const thirtyDays = presetPeriod(30);
  const sevenSelected = period.since === sevenDays.since && period.until === sevenDays.until;
  const thirtySelected = period.since === thirtyDays.since && period.until === thirtyDays.until;
  return <div className="filterPanel">
    <div className="periodPills" aria-label="Períodos rápidos">
      <Link className={"periodPill" + (sevenSelected ? " selected" : "")} href={presetUrl(sevenDays)}>7 dias</Link>
      <Link className={"periodPill" + (thirtySelected ? " selected" : "")} href={presetUrl(thirtyDays)}>30 dias</Link>
    </div>
    <form className="filterForm" method="GET">
      <label>De <input type="date" name="since" required defaultValue={period.since}/></label>
      <label>Até <input type="date" name="until" required defaultValue={period.until}/></label>
      <button type="submit">Aplicar filtro <span aria-hidden="true">→</span></button>
    </form>
  </div>;
}

function CampaignStatus({ campaign }: { campaign?: Campaign }) {
  if (!campaign) return <span className="statusChip muted">Sem status</span>;
  const state = campaign.effective_status ?? campaign.status ?? "UNKNOWN";
  const active = state === "ACTIVE";
  return <span className={"statusChip " + (active ? "isActive" : "isPaused")}>
    <span className="statusDot" aria-hidden="true"/>
    {active ? "Ativa" : state === "PAUSED" ? "Pausada" : state.replaceAll("_", " ")}
  </span>;
}

function Comparison({ summary, currency }: { summary: InsightSummary; currency: string }) {
  const spend = finite(summary.totals.spend) ?? 0;
  const revenue = finite(summary.totals.purchaseValue) ?? 0;
  const max = Math.max(spend, revenue, 0);
  return <section className="chartPanel" aria-labelledby="comparison-heading">
    <div className="panelHeader">
      <div><div className="smallEyebrow">COMPARATIVO</div><h2 id="comparison-heading">Investimento x receita</h2>
        <p>Valores atribuídos pela Meta no período</p></div>
      <span className="panelTag">Moeda: {currency}</span>
    </div>
    <div className="comparisonBars">
      {[{ title: "Investimento", value: spend, kind: "spent" }, { title: "Receita atribuída", value: revenue, kind: "earned" }].map(item =>
        <div className="comparisonRow" key={item.kind}>
          <div className="comparisonLabel"><span>{item.title}</span><strong>{money(item.value, currency)}</strong></div>
          <div className="comparisonTrack">
            <div className={"comparisonFill " + item.kind} style={{ width: max ? (item.value / max * 100) + "%" : "0%" }}/>
          </div>
        </div>
      )}
    </div>
    <p className="microNote">Receita atribuída não corresponde a faturamento confirmado ou lucro. O ROAS não inclui CMV, taxas e frete.</p>
  </section>;
}

function CampaignTable({ summary, listing, currency }: {
  summary: InsightSummary; listing?: CampaignPage; currency: string;
}) {
  const statusById = new Map((listing?.data ?? []).map(c => [c.id, c]));
  const insightById = new Map(summary.campaigns.map(c => [c.campaignId, c]));
  // Include campaigns with no Insights in the selected period when their catalogue page is available.
  const ids = [...new Set([...insightById.keys(), ...statusById.keys()])];
  ids.sort((a, b) => (finite(insightById.get(b)?.metrics.spend) ?? 0)
    - (finite(insightById.get(a)?.metrics.spend) ?? 0));
  const highest = Math.max(0, ...ids.map(id => finite(insightById.get(id)?.metrics.spend) ?? 0));
  return <section className="campaignPanel" id="campanhas" aria-labelledby="campaign-heading">
    <div className="panelHeader tablePanelHeader">
      <div><div className="smallEyebrow">DESEMPENHO</div><h2 id="campaign-heading">Campanhas</h2>
        <p>Ordenadas pelo investimento no período selecionado</p></div>
      <span className="panelTag">{ids.length} {ids.length === 1 ? "campanha" : "campanhas"}</span>
    </div>
    {ids.length ? <div className="tableScroll"><table>
      <thead><tr><th>Campanha</th><th>Status</th><th>Investimento</th><th>Cliques</th><th>Compras</th><th>ROAS</th></tr></thead>
      <tbody>{ids.map(id => {
        const c = statusById.get(id);
        const insight = insightById.get(id);
        const metrics: InsightMetrics | undefined = insight?.metrics;
        const spend = finite(metrics?.spend) ?? 0;
        return <tr key={id}>
          <td><div className="campaignCell"><span className="campaignAvatar"><Icon name="activity"/></span>
            <span className="campaignDetails"><strong>{c?.name ?? insight?.campaignName ?? id}</strong>
              <small>ID {id}</small></span></div></td>
          <td><CampaignStatus campaign={c}/></td>
          <td><div className="spendCell"><strong>{money(spend, currency)}</strong>
            <div className="tinyTrack"><span style={{width: highest ? (spend / highest * 100) + "%" : "0%"}}/></div>
          </div></td>
          <td>{number(metrics?.clicks ?? 0)}</td>
          <td>{number(metrics?.purchases ?? 0)}</td>
          <td><span className="roasValue">{multiplier(metrics?.roas)}</span></td>
        </tr>;
      })}</tbody>
    </table></div> :
    <div className="emptyPanel"><div className="emptyIcon"><Icon name="activity"/></div>
      <strong>Nenhuma campanha no período</strong>
      <p>Não foram encontrados dados para esse intervalo de datas.</p></div>}
    {listing?.after && <div className="tableFootnote">A lista de status mostra apenas a primeira página do catálogo de campanhas. Os Insights consolidados são independentes dela.</div>}
    <div className="tableFootnote">Compras e receita são eventos atribuídos pela Meta; não representam pedidos conciliados com a loja.</div>
  </section>;
}

export default async function Home({ searchParams }: { searchParams: Promise<Params> }) {
  const params = await searchParams;
  const since = typeof params?.since === "string" ? params.since : undefined;
  const until = typeof params?.until === "string" ? params.until : undefined;
  const period = resolvePeriod(since, until);

  const query = new URLSearchParams({ since: period.since, until: period.until });
  const [account, listing, summary] = await Promise.all([
    readApi<MetaAccount>("/api/v1/meta/account"),
    readApi<CampaignPage>("/api/v1/meta/campaigns?limit=100"),
    period.error ? Promise.resolve({ error: period.error } as Load<InsightSummary>) :
      readApi<InsightSummary>("/api/v1/meta/insights/summary?" + query.toString()),
  ]);
  const data = summary.data;
  const currency = account.data?.currency ?? "BRL";
  const ready = Boolean(data);
  const complete = data?.complete ?? false;
  const metrics = data?.totals;

  return <main className="shell">
    <Sidebar/>
    <div className="content">
      <div className="topbar">
        <div className="breadcrumb">Workspace pessoal <span>/</span> <strong>Visão geral</strong></div>
        <div className="topbarRight"><span className="topBadge"><span className="liveDot"/> Meta Ads · Somente leitura</span><span className="avatar">AD</span></div>
      </div>
      <div className="dashboardBody">
        <header className="dashboardHeading">
          <div><div className="eyebrow"><span className="eyebrowLine"/> PERFORMANCE DE CAMPANHAS</div>
            <h1>Painel de desempenho<span className="titlePeriod">.</span></h1>
            <p>Uma visão clara do investimento e dos resultados das suas campanhas Meta Ads.</p></div>
          <span className="versionChip">ADPILOT / MVP</span>
        </header>

        <PeriodFilter period={period}/>

        {period.error && <div className="alertBanner error" role="alert"><strong>Filtro inválido</strong><span>{period.error}</span></div>}
        {!period.error && !ready && <div className="alertBanner error" role="alert">
          <strong>Não foi possível carregar os Insights.</strong><span>{summary.error ?? "O backend não retornou dados."} Os valores não serão estimados.</span>
        </div>}
        {data && !complete && <div className="alertBanner caution" role="status">
          <strong>Atenção: dados parciais</strong><span>A Meta possui mais páginas do que o limite consultado. Os totais abaixo abrangem somente {data.recordsFetched} registros e não devem ser tratados como totais definitivos.</span>
        </div>}
        {ready && complete && <div className="syncLine">
          <span className="successCircle">✓</span> Dados consultados da Meta
          <span className="syncSeparator">·</span>
          {readableDate(period.since)} – {readableDate(period.until)}
          <span className="syncSeparator">·</span>
          {data!.recordsFetched} registros em {data!.pagesFetched} {data!.pagesFetched === 1 ? "página" : "páginas"}
        </div>}

        <div className="sectionIntro"><div><h2>Visão financeira</h2><p>Indicadores de atribuição da Meta para o intervalo escolhido</p></div><span className="sectionContext">{account.data?.name ?? "Conta Meta Ads"}</span></div>
        <div className="kpiGrid">
          <Card label="Investimento" value={ready ? money(metrics?.spend, currency) : "—"}
            hint="Valor aplicado em anúncios" icon={<span>↗</span>}/>
          <Card label="Receita atribuída" value={ready ? money(metrics?.purchaseValue, currency) : "—"}
            hint="Conversões atribuídas pela Meta" icon={<span>↗</span>}/>
          <Card label="ROAS atribuído" value={ready ? multiplier(metrics?.roas) : "—"}
            hint="Receita atribuída / investimento" accent icon={<Icon name="arrow"/>}/>
          <Card label="Compras atribuídas" value={ready ? number(metrics?.purchases) : "—"}
            hint="Não são pedidos pagos conciliados" icon={<span>◎</span>}/>
        </div>

        <div className="detailsGrid">
          <section className="miniMetrics" aria-labelledby="traffic-heading">
            <div className="panelHeader">
              <div><div className="smallEyebrow">ALCANCE & TRÁFEGO</div><h2 id="traffic-heading">Indicadores de tráfego</h2>
              <p>Interações e eficiência dos anúncios</p></div>
            </div>
            <div className="miniMetricsGrid">
              <div><span>Impressões</span><strong>{ready ? number(metrics?.impressions) : "—"}</strong></div>
              <div><span>Cliques</span><strong>{ready ? number(metrics?.clicks) : "—"}</strong></div>
              <div><span>CTR</span><strong>{ready ? percentage(metrics?.ctr) : "—"}</strong></div>
              <div><span>CPC médio</span><strong>{ready ? money(metrics?.cpc, currency) : "—"}</strong></div>
            </div>
          </section>
          {data ? <Comparison summary={data} currency={currency}/> :
          <section className="chartPanel"><div className="panelHeader"><div><div className="smallEyebrow">COMPARATIVO</div>
            <h2>Investimento x receita</h2><p>Dados não disponíveis para comparação</p></div></div>
            <div className="chartPlaceholder">Aguardando Insights válidos da Meta.</div></section>}
        </div>

        {data ? <CampaignTable summary={data} listing={listing.data} currency={currency}/> :
        <section className="campaignPanel" id="campanhas"><div className="panelHeader"><div><div className="smallEyebrow">DESEMPENHO</div>
          <h2>Campanhas</h2><p>Não foi possível carregar os indicadores do período</p></div></div>
          <div className="emptyPanel">Corrija a integração ou o filtro de datas para consultar as campanhas.</div></section>}

        <footer className="dashboardFooter">
          <span>© AdPilot · Dados em tempo de consulta · Somente leitura</span>
          <span>Meta Ads conectada {account.data ? "· " + account.data.id : ""} · Nuvemshop ainda não integrada</span>
        </footer>
      </div>
    </div>
  </main>;
}
