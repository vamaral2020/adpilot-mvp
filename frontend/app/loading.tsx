export default function Loading() {
  return <div className="loadingView" role="status" aria-live="polite">
    <div className="loadingTop"><span className="loadingLogo">A.</span> AdPilot <span className="loadingTag">Carregando dados da Meta</span></div>
    <div className="loadingContent">
      <div className="loadingStrip short skeleton" aria-hidden="true"/>
      <div className="loadingStrip large skeleton" aria-hidden="true"/>
      <div className="loadingStrip medium skeleton" aria-hidden="true"/>
      <div className="loadingCards">{Array.from({ length: 4 }, (_, index) =>
        <div key={index} className="loadingCard skeleton" aria-hidden="true"/>)}</div>
      <div className="loadingPanels"><div className="skeleton"/><div className="skeleton"/></div>
      <p>Consultando métricas reais. Nenhum dado demonstrativo será exibido.</p>
    </div>
  </div>;
}
