import Link from "next/link";
import { analyzeMedia, compareSources } from "../lib/performance";
import {
  money, number, percentage, multiplier, presetPeriod, readableDate, resolvePeriod, finite,
  type MetaAccount, type CampaignPage, type Campaign, type InsightSummary,
  type InsightMetrics, type Period, type CommerceSummary, type PaymentAudit,
  type AuditBucket, auditMissingPercentage, verifiedMer
} from "../lib/dashboard";

export const dynamic = "force-dynamic";

type Params = { since?: string | string[]; until?: string | string[] };
type Load<T> = { data?: T; error?: string };
type NuvemshopStatus = {
  status: "NOT_CONFIGURED" | "NOT_CONNECTED" | "CONNECTED";
  configured: boolean;
  storeId: string | null;
  scopes: string | null;
  connectedAt: string | null;
};

async function readApi<T>(path: string, timeoutMs = 45_000): Promise<Load<T>> {
  const username = process.env.ADPILOT_ADMIN_USERNAME ?? "";
  const password = process.env.ADPILOT_ADMIN_PASSWORD ?? "";
  const origin = process.env.INTERNAL_API_URL ?? "http://localhost:18080";
  if (!username || !password) return { error: "Autenticação interna não configurada." };
  try {
    // Server-side only: credentials never reach client JavaScript.
    const response = await fetch(origin + path, {
      headers: { Authorization: "Basic " + Buffer.from(username + ":" + password).toString("base64") },
      cache: "no-store",
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (!response.ok) {
      const label = response.status === 503 ? "Serviço indisponível" :
        response.status === 502 ? "Erro na integração externa" :
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
      <Link className="nav" href="#analise-investimento"><Icon name="chart"/> Eficiência de mídia</Link>
      <Link className="nav" href="#comercio"><Icon name="layers"/> Desempenho comercial</Link>
      <Link className="nav" href="#auditoria"><Icon name="chart"/> Auditoria de pagamentos</Link>
      <Link className="nav" href="#integracoes"><Icon name="settings"/> Integrações</Link>
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
  const insightById = new Map(summary.campaigns.map(c => [c.campaignId, c]));
  const statusById = new Map((listing?.data ?? []).map(c => [c.id, c]));
  const ids = [...new Set([...insightById.keys(), ...statusById.keys()])];
  const media = analyzeMedia(summary);
  const analyzedById = new Map((media?.rows ?? []).map(c => [c.id, c]));
  ids.sort((a, b) => (finite(insightById.get(b)?.metrics.spend) ?? 0)
    - (finite(insightById.get(a)?.metrics.spend) ?? 0) || a.localeCompare(b));
  const highest = Math.max(0, ...ids.map(id => finite(insightById.get(id)?.metrics.spend) ?? 0));
  return <section className="campaignPanel" id="campanhas" aria-labelledby="campaign-heading">
    <div className="panelHeader tablePanelHeader">
      <div><div className="smallEyebrow">EFICIÊNCIA DA META ADS</div><h2 id="campaign-heading">Desempenho por campanha</h2>
        <p>Investimento e compras atribuídas pela Meta no período selecionado</p></div>
      <span className="panelTag">{ids.length} {ids.length === 1 ? "campanha" : "campanhas"}</span>
    </div>
    {ids.length ? <div className="tableScroll"><table className="efficiencyTable">
      <thead><tr><th>Campanha</th><th>Status</th><th>Investimento</th><th>% do gasto</th>
        <th>Cliques</th><th>Compras Meta</th><th>Receita atribuída</th><th>Custo/compra Meta</th><th>ROAS Meta</th></tr></thead>
      <tbody>{ids.map(id => {
        const c = statusById.get(id);
        const insight = insightById.get(id);
        const metrics: InsightMetrics | undefined = insight?.metrics;
        const calculated = analyzedById.get(id);
        const spend = finite(metrics?.spend);
        return <tr key={id}>
          <td><div className="campaignCell"><span className="campaignAvatar"><Icon name="activity"/></span>
            <span className="campaignDetails"><strong>{c?.name ?? insight?.campaignName ?? id}</strong>
              <small>ID {id}</small></span></div></td>
          <td><CampaignStatus campaign={c}/></td>
          <td><div className="spendCell"><strong>{money(spend, currency)}</strong>
            <div className="tinyTrack"><span style={{width: highest && spend !== null
              ? (spend / highest * 100) + "%" : "0%"}}/></div>
          </div></td>
          <td>{calculated ? percentage(calculated.spendShare) : "—"}</td>
          <td>{metrics ? number(metrics.clicks) : "—"}</td>
          <td>{metrics ? number(metrics.purchases, 2) : "—"}</td>
          <td>{metrics ? money(metrics.purchaseValue, currency) : "—"}</td>
          <td>{calculated ? money(calculated.costPerAttributedPurchase, currency) : "—"}</td>
          <td><span className="roasValue">{metrics ? multiplier(metrics.roas) : "—"}</span></td>
        </tr>;
      })}</tbody>
    </table></div> :
    <div className="emptyPanel"><div className="emptyIcon"><Icon name="activity"/></div>
      <strong>Nenhuma campanha no período</strong>
      <p>Não foram encontrados dados para esse intervalo de datas.</p></div>}
    {listing?.after && <div className="tableFootnote">A lista de status mostra apenas a primeira página do catálogo de campanhas. Os Insights consolidados são independentes dela.</div>}
    {!media && <div className="tableFootnote" role="status">Participação no investimento e custo por compra ocultos: totais da Meta incompletos ou inconsistentes.</div>}
    <div className="tableFootnote">Compras, receitas e custo por compra são **atribuídos pela Meta**; não correspondem a pedidos conciliados com a Nuvemshop. Status é o estado atual da campanha, não necessariamente o estado no período.</div>
  </section>;
}

function MediaAnalysisPanel({ meta, store, currency }: {
  meta?: InsightSummary; store?: CommerceSummary; currency?: string;
}) {
  const media = analyzeMedia(meta);
  const currencyOk = typeof currency === "string" && /^[A-Z]{3}$/.test(currency);
  const comparable = currencyOk && store?.storeCurrency === currency
    ? compareSources(meta, store) : null;
  return <section className="mediaAnalysis" id="analise-investimento"
      aria-labelledby="media-analysis-heading">
    <div className="panelHeader">
      <div><div className="smallEyebrow">INTELIGÊNCIA COMERCIAL · SOMENTE LEITURA</div>
        <h2 id="media-analysis-heading">Eficiência do investimento</h2>
        <p>Diagnóstico de investimento, compras atribuídas e vendas efetivadas — sem atribuição artificial.</p></div>
      <span className="panelTag">{media && currencyOk ? "Meta consolidada" : "Análise indisponível"}</span>
    </div>
    {!media || !currencyOk ? <div className="commerceEmpty" role="status">
      Para calcular a eficiência, precisamos de Insights completos da Meta e da moeda da conta confirmada.
      Dados parciais não produzem indicadores conclusivos.
    </div> : <>
      <div className="analysisStatGrid">
        <div><span>Custo por compra atribuída · Meta</span>
          <strong>{money(media.accountCostPerAttributedPurchase, currency)}</strong>
          <small>Gasto total Meta / compras atribuídas; não é CAC real.</small></div>
        <div><span>Gasto sem compras atribuídas · Meta</span>
          <strong>{money(media.spendWithoutAttributedPurchases, currency)}</strong>
          <small>{number(media.campaignsWithoutAttributedPurchases)} campanha(s) com gasto e zero compras atribuídas.</small></div>
        <div><span>Campanha de maior investimento</span>
          <strong className="analysisName">{media.topSpender?.name ?? "—"}</strong>
          <small>{media.topSpender ? money(media.topSpender.spend, currency)
            + " · " + percentage(media.topSpender.spendShare) + " do gasto" : "Sem investimento no período"}</small></div>
      </div>
      {comparable ? <div className="sourceComparison">
        <div className="sourceColumn">
          <span className="smallEyebrow">NUVEMSHOP · EFETIVADAS</span>
          <h3>{money(comparable.paidRevenue, currency)}</h3>
          <p>{number(comparable.paidOrders)} vendas pagas, não canceladas, agrupadas por paid_at (UTC).</p>
        </div>
        <div className="sourceColumn">
          <span className="smallEyebrow">META · ATRIBUIÇÃO</span>
          <h3>{money(comparable.attributedRevenue, currency)}</h3>
          <p>{number(comparable.attributedPurchases, 2)} compras atribuídas nos Insights da Meta.</p>
        </div>
        <div className="sourceColumn">
          <span className="smallEyebrow">INDICADORES INDEPENDENTES</span>
          <h3>MER {multiplier(comparable.mer)}</h3>
          <p>ROAS atribuído pela Meta: {multiplier(comparable.attributedRoas)}.</p>
        </div>
      </div> : <p className="analysisUnverified" role="status">
        Comparação entre loja e Meta indisponível: é necessário ter períodos iguais,
        moedas coincidentes, paginação completa e totais da Meta consistentes nas duas consultas.
      </p>}
      <p className="analysisMethod">
        <strong>Interpretação:</strong> uma campanha sem compras atribuídas não é necessariamente
        ineficiente: a atribuição depende de instrumentação, janela de conversão e eventos.
        Os totais da loja não estão vinculados a campanhas. Não recomendamos alterar
        orçamento ou pausar anúncios automaticamente com esses indicadores.
      </p>
    </>}
  </section>;
}

function CommercePanel({ summary, error, currency }: {
  summary?: CommerceSummary; error?: string; currency: string;
}) {
  const shopCurrency = summary?.storeCurrency ?? "BRL";
  const daily = summary?.daily.slice(-14) ?? [];
  const highest = Math.max(0, ...daily.map(day => finite(day.paidRevenue) ?? 0));
  const completeOrders = summary?.ordersComplete ?? false;
  const trustedMer = verifiedMer(summary);
  const currencyMismatch = summary?.warnings.includes("CURRENCY_MISMATCH");
  const missingCurrency = summary?.warnings.includes("CURRENCY_NOT_ESTABLISHED");
  return <section className="commercePanel" id="comercio" aria-labelledby="commerce-heading">
    <div className="panelHeader">
      <div>
        <div className="smallEyebrow">NUVEMSHOP + META ADS</div>
        <h2 id="commerce-heading">Desempenho comercial</h2>
        <p>Vendas pagas e não canceladas, por data de pagamento, comparadas com o gasto em Meta Ads. Somente leitura.</p>
      </div>
      <span className="panelTag">{summary?.complete ? "Dados completos" : "Consolidação"}</span>
    </div>
    {!summary && <div className="commerceEmpty" role="status">
      {error ?? "Aguardando conexão da loja e dados do período."}
      <p>Os indicadores de campanhas Meta Ads permanecem independentes.</p>
    </div>}
    {summary && <>
      {!summary.complete && <div className="alertBanner caution" role="status">
        <strong>Consolidação parcial</strong>
        <span>{!summary.ordersPagesComplete ? "A paginação dos pedidos não terminou. " : ""}
          {summary.paidOrdersWithoutDate > 0 ? summary.paidOrdersWithoutDate + " pedido(s) pago(s) sem data de pagamento válida; não é possível determinar se pertencem ao período. Receita total, ticket médio e MER ficam indisponíveis. " : ""}
          {!summary.metaComplete ? "Os Insights da Meta também estão incompletos; gasto e MER não são exibidos como totais." : ""}
        </span>
      </div>}
      {currencyMismatch && <div className="alertBanner caution" role="status">
        <strong>Moedas diferentes</strong>
        <span>Loja: {summary.storeCurrency}; Meta: {summary.metaCurrency}. Sem conversão cambial, o MER fica indisponível.</span>
      </div>}
      <div className="kpiGrid commerceKpis">
        <Card label="Receita paga da loja"
          value={completeOrders ? money(summary.paidRevenue, shopCurrency) : "—"}
          hint="Totais de pedidos pagos, por paid_at" icon={<span>◎</span>}/>
        <Card label="Pedidos pagos"
          value={completeOrders ? number(summary.paidOrders) : "—"}
          hint="Pedidos pagos, não cancelados, com data no intervalo" icon={<Icon name="layers"/>}/>
        <Card label="Ticket médio pago"
          value={completeOrders ? money(summary.averageTicket, shopCurrency) : "—"}
          hint="Receita paga / quantidade de pedidos pagos" icon={<span>↗</span>}/>
        <Card label="MER"
          value={multiplier(trustedMer)}
          hint="Receita paga total / gasto Meta · não é atribuição" accent icon={<Icon name="chart"/>}/>
      </div>
      <div className="commerceDetails">
        <div className="commerceDaily">
          <div className="smallEyebrow">RECEITA POR PAGAMENTO</div>
          <h3>Vendas por dia (UTC)</h3>
          {completeOrders && daily.length ? <div className="commerceBars">
            {daily.map(day => <div className="commerceDay" key={day.date}>
              <span className="commerceBarTrack" aria-label={day.date + ": " + money(day.paidRevenue, shopCurrency)}>
                <span className="commerceBarFill"
                  style={{ height: highest > 0 ? (finite(day.paidRevenue) ?? 0) / highest * 100 + "%" : "0%" }}/>
              </span>
              <small>{readableDate(day.date)}</small>
            </div>)}
          </div> : <div className="commerceNoData">Série diária indisponível enquanto a paginação não for concluída.</div>}
          {completeOrders && daily.length > 0 && <p className="microNote">
            Exibindo os últimos {daily.length} dias do filtro. Totais acima abrangem todo o intervalo.</p>}
        </div>
        <div className="commerceMethod">
          <div className="smallEyebrow">LEITURA CORRETA</div>
          <h3>MER não é ROAS atribuído</h3>
          <div className="commerceMetric"><span>Gasto Meta Ads</span><strong>
            {summary.metaComplete ? money(summary.mediaSpend, summary.metaCurrency ?? currency) : "—"}</strong></div>
          <div className="commerceMetric"><span>ROAS atribuído pela Meta</span><strong>
            {summary.metaComplete ? multiplier(summary.metaRoas) : "—"}</strong></div>
          <div className="commerceMetric"><span>MER (loja inteira)</span><strong>{multiplier(trustedMer)}</strong></div>
          <p className="commerceNote">
            ROAS representa eventos atribuídos pela Meta; MER compara receita de pedidos pagos não cancelados com o gasto em anúncios, sem atribuir pedidos a campanhas.
            Os pagamentos são agrupados pelo dia UTC e os Insights da Meta usam o fuso da conta de anúncios; pequenas diferenças de corte podem ocorrer.
            Não são descontados CMV, taxas, frete ou devoluções.
          </p>
          {summary.paidOrdersWithoutDate > 0 && summary.ordersPagesComplete && <p className="commerceNote" role="status">
            Pedidos pagos com data identificada no intervalo: {number(summary.datedPaidOrders)}.
            Receita datada identificada: {money(summary.datedPaidRevenue, summary.storeCurrency ?? currency)}.
            Estes valores são parciais, não substituem o faturamento total da loja.
          </p>}
          <p className="commerceNote">
            Pedidos cancelados excluídos: {number(summary.cancelledOrdersIgnored)}.
            Datas de pagamento recuperadas pela consulta individual: {number(summary.paidDatesRecovered)}
            ({number(summary.individualLookups)} consultas adicionais).
          </p>
          {summary.inconsistentDetails > 0 && <p className="commerceNote">
            Foram detectadas {number(summary.inconsistentDetails)} divergências entre listagem e detalhe.
            Os totais comerciais não serão considerados conclusivos até nova conferência.
          </p>}
          {missingCurrency && <p className="commerceNote">Moeda da loja não identificada no período: MER indisponível.</p>}
          <p className="commerceFoot">{summary.ordersFetched} registros de pedidos consultados em {summary.pagesFetched} páginas.</p>
        </div>
      </div>
    </>}
  </section>;
}

function AuditBuckets({ heading, rows }: { heading: string; rows: AuditBucket[] }) {
  return <div className="auditGroup">
    <h3>{heading}</h3>
    {rows.length ? <ul>
      {rows.map(item => <li key={item.label}>
        <span>{item.label === "unknown" ? "Data indisponível" :
          item.label === "no_app_id" ? "Sem app_id" :
          item.label === "has_app_id" ? "Com app_id" :
          item.label === "not-provided" ? "Gateway não informado" :
          item.label === "missing" ? "Não informado" :
          item.label === "other" ? "Outro / não classificado" :
          item.label}</span>
        <strong>{number(item.count)}</strong>
      </li>)}
    </ul> : <p className="auditNoRows">Nenhum registro nesta categoria.</p>}
  </div>;
}

function PaymentAuditPanel({ audit, error }: { audit?: PaymentAudit; error?: string }) {
  const missing = audit ? audit.paidWithoutDate + audit.paidWithInvalidDate : 0;
  const pct = auditMissingPercentage(audit);
  return <section className="auditPanel" id="auditoria" aria-labelledby="audit-heading">
    <div className="panelHeader">
      <div>
        <div className="smallEyebrow">QUALIDADE DOS DADOS · NUVEMSHOP</div>
        <h2 id="audit-heading">Auditoria de pagamentos</h2>
        <p>Diagnóstico agregado de toda a listagem de pedidos, independente do filtro de datas acima.</p>
      </div>
      <span className="panelTag">{audit?.complete ? "Listagem completa" : "Diagnóstico de leitura"}</span>
    </div>
    {!audit ? <div className="commerceEmpty" role="status">
      {error ?? "Não foi possível consultar a auditoria."}
    </div> : <>
      {!audit.complete && <div className="alertBanner caution" role="status">
        <strong>Leitura parcial</strong>
        <span>A Nuvemshop não retornou a lista integral de pedidos nos limites consultados.
          As contagens abaixo são apenas da amostra lida.</span>
      </div>}
      <div className="auditStats">
        <div><span>Pedidos consultados</span><strong>{number(audit.uniqueOrders)}</strong>
          <small>{audit.pagesFetched} página(s) consultada(s)</small></div>
        <div><span>Pagos identificados</span><strong>{number(audit.paidOrders)}</strong>
          <small>Independentemente do período</small></div>
        <div><span>Pagos sem data válida</span><strong>{number(missing)}</strong>
          <small>{number(audit.paidWithoutDate)} ausentes · {number(audit.paidWithInvalidDate)} inválidas</small></div>
        <div><span>Proporção sem data</span><strong>{pct === null ? "—" : percentage(pct)}</strong>
          <small>Dos pedidos pagos; só com paginação completa</small></div>
      </div>
      {missing > 0 && <p className="auditWarning">
        Não é possível determinar o dia do pagamento desses pedidos. A data de criação
        é apenas um indício de origem e <strong>não substitui paid_at</strong>.
      </p>}
      <div className="auditGroups">
        <AuditBuckets heading="Mês de criação (UTC) dos pagos sem data" rows={audit.undatedByCreationMonth}/>
        <AuditBuckets heading="Tipo de gateway" rows={audit.undatedByGateway}/>
        <AuditBuckets heading="Origem do pedido" rows={audit.undatedByStorefront}/>
        <AuditBuckets heading="Associação com aplicativo" rows={audit.undatedByAppAssociation}/>
        <AuditBuckets heading="Status do pedido" rows={audit.undatedByOrderStatus}/>
        <AuditBuckets heading="Status de pagamento (todos)" rows={audit.paymentStatuses}/>
      </div>
      <p className="auditNote">
        Esta auditoria mede a qualidade dos dados brutos da Nuvemshop, inclusive pedidos cancelados;
        suas contagens de datas ausentes na listagem não são indicadores de vendas efetivadas.
        Categorias são indícios para investigação, não provas de origem.
        A consolidação comercial pode recuperar paid_at individualmente e ignora cancelados.
        Nenhum pedido foi modificado.
      </p>
    </>}
  </section>;
}

export default async function Home({ searchParams }: { searchParams: Promise<Params> }) {
  const params = await searchParams;
  const since = typeof params?.since === "string" ? params.since : undefined;
  const until = typeof params?.until === "string" ? params.until : undefined;
  const period = resolvePeriod(since, until);

  const query = new URLSearchParams({ since: period.since, until: period.until });
  const [account, listing, summary, shop, commerce, audit] = await Promise.all([
    readApi<MetaAccount>("/api/v1/meta/account"),
    readApi<CampaignPage>("/api/v1/meta/campaigns?limit=100"),
    period.error ? Promise.resolve({ error: period.error } as Load<InsightSummary>) :
      readApi<InsightSummary>("/api/v1/meta/insights/summary?" + query.toString()),
    readApi<NuvemshopStatus>("/api/v1/nuvemshop/status"),
    period.error ? Promise.resolve({ error: period.error } as Load<CommerceSummary>) :
      readApi<CommerceSummary>("/api/v1/commerce/summary?" + query.toString(), 120_000),
    readApi<PaymentAudit>("/api/v1/nuvemshop/payments/audit"),
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

        <MediaAnalysisPanel meta={data} store={commerce.data} currency={account.data?.currency}/>

        <CommercePanel summary={commerce.data} error={commerce.error} currency={currency}/>
        {shop.data?.status === "CONNECTED" &&
          <PaymentAuditPanel audit={audit.data} error={audit.error}/>}

        <section className="shopPanel" id="integracoes" aria-labelledby="shop-heading">
          <div className="shopHeader">
            <div><div className="smallEyebrow">INTEGRAÇÕES</div><h2 id="shop-heading">Nuvemshop</h2>
              <p>Conecte sua loja para consultar produtos e pedidos, sem modificar dados.</p></div>
            <span className={"statusChip " + (shop.data?.status === "CONNECTED" ? "isActive" : "muted")}>
              {shop.data?.status === "CONNECTED" ? "Conectada" :
                shop.data?.status === "NOT_CONNECTED" ? "Aguardando autorização" : "Não configurada"}
            </span>
          </div>
          {shop.data?.status === "CONNECTED" ? (
            <p className="shopDetails"><span className="shopCheck">✓</span>
              Loja <strong>{shop.data.storeId}</strong> conectada com acesso de leitura.
              A API já permite consultar produtos, variantes e pedidos.</p>
          ) : shop.data?.configured ? (
            <div className="shopActions">
              <p>Você será direcionado à Nuvemshop para aprovar as permissões de leitura.</p>
              <form action="/api/nuvemshop/connect" method="POST">
                <button type="submit">Conectar minha loja <span aria-hidden="true">↗</span></button>
              </form>
            </div>
          ) : <p className="shopDetails">
            {shop.error ?? "Configure o aplicativo, o callback HTTPS e as variáveis NUVEMSHOP_* no ambiente para habilitar a conexão."}
          </p>}
        </section>

        <footer className="dashboardFooter">
          <span>© AdPilot · Dados em tempo de consulta · Somente leitura</span>
          <span>Meta Ads conectada {account.data ? "· " + account.data.id : ""} · Nuvemshop {shop.data?.status === "CONNECTED" ? "conectada" : "não conectada"}</span>
        </footer>
      </div>
    </div>
  </main>;
}
