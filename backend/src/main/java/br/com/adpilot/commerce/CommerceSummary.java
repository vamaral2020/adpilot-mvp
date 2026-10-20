package br.com.adpilot.commerce;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * MER = total da receita paga registrada pela loja / gasto com Meta Ads.
 * MER nao atribui vendas a campanhas; ROAS e a atribuicao reportada pela Meta.
 * Totais nulos representam dados parciais, nunca zero presumido.
 * datedPaid* so informa pedidos com data conhecida, nao o faturamento total
 * do periodo quando paidOrdersWithoutDate > 0.
 */
public record CommerceSummary(
        LocalDate since, LocalDate until,
        boolean ordersComplete, boolean metaComplete, boolean complete,
        int pagesFetched, int ordersFetched,
        boolean ordersPagesComplete, int paidOrdersWithoutDate,
        int cancelledOrdersIgnored, int paidDatesRecovered,
        int individualLookups, int inconsistentDetails,
        Long datedPaidOrders, BigDecimal datedPaidRevenue,
        Long paidOrders, BigDecimal paidRevenue, BigDecimal averageTicket,
        String storeCurrency, String metaCurrency,
        BigDecimal mediaSpend, BigDecimal metaAttributedRevenue, BigDecimal metaRoas,
        BigDecimal mer, List<DailySales> daily, List<String> warnings) {

    public record DailySales(LocalDate date, long paidOrders, BigDecimal paidRevenue) { }
}
