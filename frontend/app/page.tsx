type Overview = {
  app: string;
  release: string;
  database: string;
  integrations: Record<string, string>;
};

async function getOverview(): Promise<Overview | null> {
  const base = process.env.INTERNAL_API_URL ?? "http://localhost:18080";
  try {
    const res = await fetch(`${base}/api/v1/system/overview`, { cache: "no-store" });
    if (!res.ok) return null;
    return (await res.json()) as Overview;
  } catch {
    return null;
  }
}

function Status({ state }: { state: string }) {
  const ok = state === "CONNECTED";
  return <span className={`pill ${ok ? "ok" : "pending"}`}>{ok ? "Conectado" : state === "NOT_CONFIGURED" ? "Não configurado" : state === "OFFLINE" ? "Indisponível" : state}</span>;
}

export const dynamic = "force-dynamic";

export default async function Home() {
  const overview = await getOverview();
  const dbState = overview?.database ?? "OFFLINE";
  const meta = overview?.integrations.META_ADS ?? "OFFLINE";
  const store = overview?.integrations.NUVEMSHOP ?? "OFFLINE";

  return (
    <main className="shell">
      <aside className="sidebar">
        <div className="brand"><span className="brandLogo">A</span><span>AdPilot</span></div>
        <div className="navLabel">CONTROLE</div>
        <div className="nav active">▦ <span>Visão geral</span></div>
        <div className="nav disabled">↗ <span>Campanhas</span></div>
        <div className="nav disabled">◈ <span>Recomendações IA</span></div>
        <div className="nav disabled">◫ <span>Produtos e pedidos</span></div>
        <div className="navLabel gapTop">CONFIGURAÇÕES</div>
        <div className="nav disabled">⚙ <span>Integrações</span></div>
        <div className="sideBottom"><span className="dot"/> Ambiente de desenvolvimento<br/><small>Somente uso local</small></div>
      </aside>
      <section className="content">
        <header className="top"><span>Workspace pessoal</span><span className="version">MVP 0.1.0 · Fundação</span></header>
        <div className="heading"><div><div className="eyebrow">ADPILOT / PAINEL</div><h1>Visão geral</h1><p>Acompanhe a preparação das integrações antes de importar dados reais.</p></div><span className="stage">ETAPA 1 DE 4</span></div>
        <div className="notice"><span className="noticeIcon">ⓘ</span><div><strong>Estrutura inicial do projeto</strong><p>O painel ainda não consulta campanhas nem pedidos. Nenhuma integração externa foi ativada e nenhum orçamento será alterado.</p></div></div>
        <div className="stats">
          <div className="stat"><span>Investimento em anúncios</span><strong>—</strong><small>Aguardando conexão Meta</small></div>
          <div className="stat"><span>Receita atribuída</span><strong>—</strong><small>Sem métricas importadas</small></div>
          <div className="stat"><span>ROAS</span><strong>—</strong><small>Sem campanhas vinculadas</small></div>
          <div className="stat"><span>Pedidos pagos</span><strong>—</strong><small>Aguardando Nuvemshop</small></div>
        </div>
        <div className="sectionTitle"><div><h2>Conexões do sistema</h2><p>Estado técnico das integrações no backend</p></div></div>
        <div className="connections">
          <div className="connection"><div className="connectorIcon db">▤</div><div><strong>PostgreSQL</strong><small>Banco de dados da aplicação</small></div><Status state={dbState}/></div>
          <div className="connection"><div className="connectorIcon meta">∞</div><div><strong>Meta Ads</strong><small>Campanhas, anúncios e métricas</small></div><Status state={meta}/></div>
          <div className="connection"><div className="connectorIcon shop">▣</div><div><strong>Nuvemshop</strong><small>Catálogo, vendas e estoque</small></div><Status state={store}/></div>
        </div>
        <div className="bottomGrid"><div className="panel"><h2>Próximos marcos</h2><ol><li>Validar a inicialização dos containers</li><li>Configurar leitura da Meta Marketing API</li><li>Autorizar Nuvemshop via OAuth</li><li>Importar métricas e pedidos</li></ol></div><div className="panel"><h2>Segurança por padrão</h2><p>Sem chaves incorporadas ao código, sem endpoints de gestão expostos e sem operações de escrita nas plataformas de anúncios.</p><div className="safe">✓ Nenhuma campanha será criada ou modificada nesta fase</div></div></div>
        <footer>AdPilot · Fundamentos da plataforma · Projeto próprio</footer>
      </section>
    </main>
  );
}
