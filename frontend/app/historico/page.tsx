import Link from "next/link";
import { graphBars, sums, type AnalyticsHistory } from "../../lib/history";
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

function Trend({ title, rows, currency, formatter }: {
  title: string; rows: { date: string; value: number }[]; currency?: string;
  formatter: (value: number) => string;
}) {
  const bars = graphBars(rows);
  return <section className="historyCard" aria-label={title}>
    <h2>{title}</h2>
    <div className="historyChart" role="img" aria-label={title + ": " + bars.map(b => b.date + " " + formatter(b.value)).join("; ")}>
      {bars.map(b => <div key={b.date} className="historyBarColumn" title={b.date + " · " + formatter(b.value)}>
        <span className="historyBar" style={{ height: b.height + "%" }} />
      </div>)}
    </div>
    <p className="historySmall">{bars.length} registros diários{currency ? " · " + currency : ""}. Barras representam apenas os dias disponíveis na base.</p>
  </section>;
}

export default async function HistoryPage({ searchParams }: { searchParams: Promise<Params> }) {
  const params = await searchParams;
  const period = resolvePeriod(typeof params.since === "string" ? params.since : undefined,
    typeof params.until === "string" ? params.until : undefined);
  const result = period.error ? { error: period.error } : await loadHistory(period.since, period.until);
  const history = result.data;
  const media = history ? sums(history.meta.map(r => ({ currency: r.currency, value: Number(r.spend) }))) : null;
  const store = history ? sums(history.store.map(r => ({ currency: r.currency, value: Number(r.paidRevenue) }))) : null;
  const compare = history?.complete && media && store && media.currency === store.currency && media.value > 0
    ? store.value / media.value : null;
  const seven = presetPeriod(7), thirty = presetPeriod(30);
  return <main className="historyShell">
    <nav className="historyNav"><Link href="/">← Visão geral</Link><strong>AdPilot / Histórico</strong></nav>
    <header className="historyHeader">
      <div><div className="smallEyebrow">ANÁLISE LOCAL · POSTGRESQL</div><h1>Histórico analítico<span className="titlePeriod">.</span></h1>
        <p>Dados persistidos pela sincronização Meta Ads e Nuvemshop, sem novas chamadas aos provedores.</p></div>
    </header>
    <div className="filterPanel">
      <div className="periodPills"><Link href={"/historico?since="+seven.since+"&until="+seven.until} className="periodPill">7 dias</Link>
        <Link href={"/historico?since="+thirty.since+"&until="+thirty.until} className="periodPill">30 dias</Link></div>
      <form className="filterForm" method="GET">
        <label>De <input name="since" type="date" required defaultValue={period.since}/></label>
        <label>Até <input name="until" type="date" required defaultValue={period.until}/></label>
        <button type="submit">Consultar</button>
      </form>
    </div>
    {result.error && <div className="alertBanner error" role="alert">{result.error}</div>}
    {history && <>
      <div className="alertBanner caution" role="status"><strong>Histórico ainda não auditado como completo.</strong>
        <span>Um dia sem registros não significa necessariamente zero vendas ou gasto. Pedidos representam seu estado mais recente; não um livro-razão de eventos.</span></div>
      <div className="historyKpis">
        <article className="historyCard"><span>Investimento Meta armazenado</span><strong>{media ? money(media.value, media.currency) : "—"}</strong><small>Moeda única exigida</small></article>
        <article className="historyCard"><span>Receita paga identificada</span><strong>{store ? money(store.value, store.currency) : "—"}</strong><small>Pedidos pagos e não cancelados</small></article>
        <article className="historyCard"><span>MER auditado</span><strong>{compare === null ? "—" : number(compare, 2) + "x"}</strong><small>Oculto enquanto a cobertura não estiver comprovada</small></article>
      </div>
      <div className="historyGrid">
        <Trend title="Investimento diário Meta" rows={history.meta.map(r => ({ date: r.date + " / " + r.currency, value: Number(r.spend) }))}
          currency={media?.currency} formatter={value => money(value, media?.currency ?? "BRL")}/>
        <Trend title="Receita diária paga (UTC)" rows={history.store.map(r => ({ date: r.date + " / " + r.currency, value: Number(r.paidRevenue) }))}
          currency={store?.currency} formatter={value => money(value, store?.currency ?? "BRL")}/>
      </div>
      <section className="historyCard">
        <h2>Últimas sincronizações registradas</h2>
        {history.sources.length === 0 ? <p>Não há checkpoints persistidos. Execute uma coleta controlada antes de interpretar o histórico.</p> :
        <div className="tableScroll"><table className="efficiencyTable"><thead><tr><th>Fonte</th><th>Recurso</th><th>Último estado</th><th>Último sucesso</th></tr></thead>
          <tbody>{history.sources.map(s => <tr key={s.provider + s.resource}><td>{s.provider}</td><td>{s.resource}</td><td>{s.lastStatus ?? "—"}</td>
            <td>{s.lastSuccess ? new Date(s.lastSuccess).toLocaleString("pt-BR", { timeZone: "UTC" }) + " UTC" : "—"}</td></tr>)}</tbody></table></div>}
      </section>
      <p className="historySmall">Os dias da Meta são reportados segundo o fuso da conta; os pagamentos da loja são agrupados em UTC. ROAS Meta e vendas da loja não são conciliação pedido-campanha. Estado de cobertura: {history.complete ? "completo" : "não verificado"}.</p>
    </>}
  </main>;
}
