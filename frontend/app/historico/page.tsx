import Link from "next/link";
import { campaignOutcome, graphBars, storeOutcome, type AnalyticsHistory } from "../../lib/history";
import { money, number, presetPeriod, resolvePeriod } from "../../lib/dashboard";

export const dynamic = "force-dynamic";

type Params = { since?: string | string[]; until?: string | string[] };

async function loadHistory(since: string, until: string): Promise<{ data?: AnalyticsHistory; error?: string }> {
  const username = process.env.ADPILOT_ADMIN_USERNAME ?? "";
  const password = process.env.ADPILOT_ADMIN_PASSWORD ?? "";
  if (!username || !password) return { error: "Credenciais internas indisponíveis." };
  try {
    const base = process.env.INTERNAL_API_URL ?? "http://localhost:18080";
    const query = new URLSearchParams({ since, until });
    const response = await fetch(base + "/api/v1/analytics/history?" + query, {
      headers: { Authorization: "Basic " + Buffer.from(username + ":" + password).toString("base64") },
      cache: "no-store", signal: AbortSignal.timeout(15000),
    });
    if (!response.ok) return { error: "Não foi possível consultar o histórico (HTTP " + response.status + ")." };
    return { data: (await response.json()) as AnalyticsHistory };
  } catch {
    return { error: "O histórico local não está disponível." };
  }
}

function Metric({ label, value, help, emphasis = false }: {
  label: string; value: string; help: string; emphasis?: boolean;
}) {
  return <article className={"historyCard historyMetric" + (emphasis ? " historyMetricEmphasis" : "")}>
    <span>{label}</span>
    <strong>{value}</strong>
    <small>{help}</small>
  </article>;
}

function Trend({ title, rows, currency, formatter }: {
  title: string; rows: { date: string; value: number }[]; currency?: string;
  formatter: (value: number) => string;
}) {
  const bars = graphBars(rows);
  return <section className="historyCard" aria-label={title}>
    <h2>{title}</h2>
    {bars.length === 0
      ? <p className="historyChartEmpty">Nenhum registro encontrado. Ausência de dados não significa resultado zero.</p>
      : <div className="historyChart" role="img" aria-label={title + ": " + bars.map(b => b.date + " " + formatter(b.value)).join("; ")}>
          {bars.map(b => <div key={b.date} className="historyBarColumn" title={b.date + " · " + formatter(b.value)}>
            <span className="historyBar" style={{ height: b.height + "%" }} />
          </div>)}
        </div>}
    {bars.length > 0 && bars.length <= 10 &&
      <div className="historyChartLabels" aria-hidden="true">
        {bars.map(b => <span key={b.date}>{b.date.slice(8, 10) + "/" + b.date.slice(5, 7)}</span>)}
      </div>}
    <p className="historySmall">{bars.length} registros diários{currency ? " · " + currency : ""}. Apenas dias presentes na base.</p>
  </section>;
}

export default async function HistoryPage({ searchParams }: { searchParams: Promise<Params> }) {
  const params = await searchParams;
  const period = resolvePeriod(typeof params.since === "string" ? params.since : undefined,
    typeof params.until === "string" ? params.until : undefined);
  const result = period.error ? { error: period.error } : await loadHistory(period.since, period.until);
  const history = result.data;
  const ads = history ? campaignOutcome(history.meta) : null;
  const shop = history ? storeOutcome(history.store) : null;
  const verifiedMer = history?.complete && ads?.spend && shop?.revenue
    && ads.spend.currency === shop.revenue.currency && ads.spend.value > 0
      ? shop.revenue.value / ads.spend.value : null;
  const seven = presetPeriod(7), thirty = presetPeriod(30);

  return <main className="historyShell">
    <nav className="historyNav"><Link href="/">← Visão geral</Link><strong>AdPilot / Histórico</strong></nav>
    <header className="historyHeader">
      <div className="smallEyebrow">ANÁLISE LOCAL · POSTGRESQL</div>
      <h1>Resultado dos anúncios<span className="titlePeriod">.</span></h1>
      <p>Entenda o investimento e as vendas atribuídas aos anúncios. A receita total da loja aparece separadamente.</p>
    </header>

    <div className="filterPanel">
      <div className="periodPills">
        <Link href={"/historico?since="+seven.since+"&until="+seven.until} className="periodPill">7 dias</Link>
        <Link href={"/historico?since="+thirty.since+"&until="+thirty.until} className="periodPill">30 dias</Link>
      </div>
      <form className="filterForm" method="GET">
        <label>De <input name="since" type="date" required defaultValue={period.since}/></label>
        <label>Até <input name="until" type="date" required defaultValue={period.until}/></label>
        <button type="submit">Consultar</button>
      </form>
    </div>

    {result.error && <div className="alertBanner error" role="alert">{result.error}</div>}
    {history && <>
      <section className="historyOutcomePanel" aria-labelledby="historyAdsTitle">
        <div className="historySectionHead">
          <div>
            <div className="smallEyebrow">PRINCIPAL · DESEMPENHO DOS ANÚNCIOS</div>
            <h2 id="historyAdsTitle">O que a Meta atribuiu aos anúncios?</h2>
            <p>Resumo de todas as campanhas da conta Meta no período selecionado. Ainda não é uma análise por campanha individual.</p>
          </div>
          <span className="historySourceTag">Fonte: Meta Ads</span>
        </div>
        <div className="historyCampaignKpis">
          <Metric label="Investimento em anúncios" value={ads?.spend ? money(ads.spend.value,ads.spend.currency) : "—"}
            help="Quanto foi gasto nos anúncios registrados."/>
          <Metric label="Compras atribuídas aos anúncios"
            value={ads?.purchases !== null && ads?.purchases !== undefined ? number(ads.purchases,2) : "—"}
            help="Compras que a Meta contabilizou como resultado dos anúncios." emphasis/>
          <Metric label="Receita atribuída aos anúncios" value={ads?.revenue ? money(ads.revenue.value,ads.revenue.currency) : "—"}
            help="Valor das compras que a Meta atribuiu à publicidade." emphasis/>
          <Metric label="ROAS atribuído · indicativo"
            value={ads?.roas !== null && ads?.roas !== undefined ? number(ads.roas,2) + "x" : "—"}
            help="Receita atribuída pela Meta ÷ investimento. Não é lucro."/>
        </div>
        <p className="historyPlainExplanation">
          {ads?.spend && ads?.revenue && ads?.purchases !== null && ads?.purchases !== undefined &&
            ads.spend.currency === ads.revenue.currency
              ? <>No período, foram registrados <strong>{money(ads.spend.value,ads.spend.currency)}</strong> em anúncios.
                  A Meta atribuiu <strong>{number(ads.purchases,2)} compra(s)</strong> e
                  <strong> {money(ads.revenue.value,ads.revenue.currency)}</strong> de receita a esses anúncios.
                  {ads.roas !== null && <> Isso representa <strong>{number(ads.roas,2)}x</strong> de retorno atribuído por real investido.</>}</>
              : <>Ainda não existem dados válidos e comparáveis suficientes para resumir investimento e compras atribuídas neste período.</>}
        </p>
        <p className="historyAttributionNote"><strong>O que significa “atribuída”?</strong> É o resultado reportado pela Meta
          conforme seus eventos e sua janela de atribuição. Não comprova que cada pedido pago na Nuvemshop
          foi causado pelo anúncio, nem representa pedidos individualmente conciliados.</p>
      </section>

      <section className="historyCampaignList historyCard" aria-labelledby="historyCampaignListTitle">
        <div className="historySectionHead">
          <div>
            <h2 id="historyCampaignListTitle">Resultado de cada campanha</h2>
            <p>Veja onde o investimento foi aplicado e quantas compras a Meta atribuiu a cada campanha.</p>
          </div>
          <span className="historySourceTag">Atribuição Meta · dados disponíveis</span>
        </div>
        {history.campaigns.length === 0
          ? <p className="historySmall">Não há resultados de campanha armazenados neste período. Isso não comprova gasto ou vendas iguais a zero.</p>
          : <div className="tableScroll"><table className="historyCampaignTable">
              <thead><tr>
                <th>Campanha</th><th>Investimento Meta</th>
                <th>Compras atribuídas</th><th>Receita atribuída</th><th>ROAS indicativo</th>
              </tr></thead>
              <tbody>{history.campaigns.map(c => {
                const rowRoas = Number.isFinite(c.spend) && c.spend > 0
                  && Number.isFinite(c.attributedRevenue) && c.attributedRevenue >= 0
                  ? c.attributedRevenue / c.spend : null;
                return <tr key={c.accountId + ":" + c.campaignId + ":" + c.currency}>
                  <td><strong>{c.name || c.campaignId}</strong><span className="historyCampaignId">ID: {c.campaignId}</span></td>
                  <td>{money(c.spend,c.currency)}</td>
                  <td>{Number.isFinite(c.attributedPurchases) && c.attributedPurchases >= 0
                    ? number(c.attributedPurchases,2) : "—"}</td>
                  <td>{money(c.attributedRevenue,c.currency)}</td>
                  <td>{rowRoas === null ? "—" : number(rowRoas,2) + "x"}</td>
                </tr>;
              })}</tbody>
            </table></div>}
        <p className="historySmall">Os resultados por campanha são reportados pela Meta, não por conciliação individual de pedidos da Nuvemshop.
          Campanhas sem registros no período podem não aparecer nesta tabela.</p>
      </section>

      <section className="historyStorePanel" aria-labelledby="historyStoreTitle">
        <div className="historySectionHead">
          <div>
            <div className="smallEyebrow">CONTEXTO · TODOS OS CANAIS</div>
            <h2 id="historyStoreTitle">Quanto a loja vendeu no total?</h2>
            <p>Vendas pagas elegíveis na Nuvemshop, independentemente da origem.</p>
          </div>
          <span className="historySourceTag">Fonte: Nuvemshop</span>
        </div>
        <div className="historyStoreKpis">
          <Metric label="Receita paga da loja" value={shop?.revenue ? money(shop.revenue.value,shop.revenue.currency) : "—"}
            help="Todos os canais: anúncios, orgânico e outras possíveis fontes."/>
          <Metric label="Pedidos pagos identificados" value={shop?.paidOrders !== null && shop?.paidOrders !== undefined
            ? number(shop.paidOrders) : "—"} help="Pagos, não cancelados e com data válida de pagamento."/>
          <Metric label="Vendas orgânicas identificadas" value="—"
            help="Ainda não há vínculo por pedido para identificar e separar as vendas orgânicas."/>
        </div>
        <p className="historyReconciliationNote"><strong>Importante:</strong> não é correto subtrair a receita atribuída pela Meta
          da receita da Nuvemshop para calcular vendas orgânicas. São fontes com regras de atribuição
          e fusos horários diferentes. MER auditado: <strong>{verifiedMer === null ? "indisponível" : number(verifiedMer,2) + "x"}</strong>.
        </p>
      </section>

      {!history.complete && <div className="alertBanner caution" role="status">
        <strong>Histórico ainda não auditado como completo.</strong>
        <span>Os valores representam os registros disponíveis, não necessariamente todas as vendas ou gastos do intervalo.
          Um dia sem registros não significa zero. Pedidos refletem seu estado mais recente, não um histórico imutável.</span>
      </div>}

      <div className="historyGrid historyOutcomeCharts">
        <Trend title="Investimento diário em anúncios · Meta"
          rows={history.meta.map(r => ({ date: r.date, value: r.spend }))}
          currency={ads?.spend?.currency} formatter={value => money(value,ads?.spend?.currency ?? "BRL")}/>
        <Trend title="Receita diária atribuída aos anúncios · Meta"
          rows={history.meta.map(r => ({ date: r.date, value: r.attributedRevenue }))}
          currency={ads?.revenue?.currency} formatter={value => money(value,ads?.revenue?.currency ?? "BRL")}/>
        <Trend title="Receita diária paga · Loja (UTC)"
          rows={history.store.map(r => ({ date: r.date, value: r.paidRevenue }))}
          currency={shop?.revenue?.currency} formatter={value => money(value,shop?.revenue?.currency ?? "BRL")}/>
      </div>

      <section className="historyCard">
        <h2>Últimas sincronizações registradas</h2>
        {history.sources.length === 0
          ? <p>Não há checkpoints persistidos. Execute uma coleta controlada antes de interpretar o histórico.</p>
          : <div className="tableScroll"><table className="efficiencyTable">
              <thead><tr><th>Fonte</th><th>Recurso</th><th>Último estado</th><th>Último sucesso</th></tr></thead>
              <tbody>{history.sources.map(s => <tr key={s.provider + s.resource}>
                <td>{s.provider}</td><td>{s.resource}</td><td>{s.lastStatus ?? "—"}</td>
                <td>{s.lastSuccess ? new Date(s.lastSuccess).toLocaleString("pt-BR", { timeZone: "UTC" }) + " UTC" : "—"}</td>
              </tr>)}</tbody>
            </table></div>}
      </section>
      <p className="historySmall">Dias Meta: fuso da conta de anúncios; pagamentos: UTC. Atribuição Meta não é conciliação pedido-campanha.
        Estado da cobertura: {history.complete ? "completo" : "não verificado"}.</p>
    </>}
  </main>;
}
