package br.com.adpilot.analytics;

import br.com.adpilot.analytics.EconomicGoalModels.Catalog;
import br.com.adpilot.analytics.EconomicGoalModels.Goal;
import br.com.adpilot.analytics.EconomicGoalModels.Input;
import br.com.adpilot.analytics.EconomicGoalModels.LinkRequest;
import br.com.adpilot.analytics.EconomicGoalModels.Result;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Recomputes client costs using integer cents and basis points. An economic
 * assumption is immutable once saved. Campaign mapping is manually confirmed,
 * never inferred from order attribution or a Meta campaign name.
 */
@Service
public class EconomicGoalService {
    private static final long LIMIT_CENTS=100_000_000L;
    private final EconomicGoalRepository repository;
    private final Clock clock;

    @Autowired
    public EconomicGoalService(EconomicGoalRepository repository) {
        this(repository,Clock.systemUTC());
    }

    EconomicGoalService(EconomicGoalRepository repository,Clock clock) {
        this.repository=repository;
        this.clock=clock;
    }

    @Transactional(readOnly=true)
    public Catalog catalog() {
        return new Catalog(repository.goals(),repository.campaigns());
    }

    public Goal save(Input input) {
        if(input==null || input.productLabel()==null ||
                input.productLabel().isBlank() ||
                input.productLabel().strip().length()<2 ||
                input.productLabel().strip().length()>120 ||
                input.productLabel().chars().anyMatch(Character::isISOControl) ||
                !validMoney(input.priceCents(),false) ||
                !validMoney(input.productCostCents(),true) ||
                !validMoney(input.paymentFixedCents(),true) ||
                !validMoney(input.shippingSubsidyCents(),true) ||
                !validMoney(input.packagingCents(),true) ||
                !validMoney(input.otherCostsCents(),true) ||
                !validPercent(input.taxBasisPoints()) ||
                !validPercent(input.paymentBasisPoints()) ||
                !validPercent(input.desiredProfitBasisPoints())) {
            throw bad("Informe nome do produto e todos os custos válidos. Use zero explicitamente quando não houver custo.");
        }

        long price=input.priceCents();
        long tax=roundPercent(price,input.taxBasisPoints());
        long payment=roundPercent(price,input.paymentBasisPoints());
        long profit=roundPercent(price,input.desiredProfitBasisPoints());
        long total=input.productCostCents()+tax+payment+input.paymentFixedCents()
                +input.shippingSubsidyCents()+input.packagingCents()+input.otherCostsCents();
        long maxCpa=price-total-profit;
        if(maxCpa<=0) throw bad("A margem não comporta publicidade preservando o lucro desejado.");
        // Ceil price / maxCpa to 2 decimal places, identical to the browser calculator.
        long roasHundredths=(price*100+maxCpa-1)/maxCpa;
        if(roasHundredths<1 || roasHundredths>10_000)
            throw bad("A meta de ROAS calculada está fora do limite de 0,01x a 100x.");
        var goal=new Goal(UUID.randomUUID(),input.productLabel().strip(),price,
                input.productCostCents(),input.taxBasisPoints(),input.paymentBasisPoints(),
                input.paymentFixedCents(),input.shippingSubsidyCents(),input.packagingCents(),
                input.otherCostsCents(),input.desiredProfitBasisPoints(),maxCpa,
                (int)roasHundredths,clock.instant());
        return repository.create(goal);
    }

    @Transactional
    public Result associate(String accountId,String campaignId,LinkRequest request) {
        checkIds(accountId,campaignId);
        if(request==null || request.goalId()==null ||
                !Boolean.TRUE.equals(request.singleProductConfirmed()))
            throw bad("Confirme explicitamente que a campanha vende uma unidade de um único produto.");
        if(!repository.goalExists(request.goalId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Meta econômica não encontrada.");
        if(!repository.eligibleCampaign(accountId,campaignId()))
            throw bad("Campanha ou moeda não identificada nos dados Meta locais. Apenas campanhas BRL com evidência coletada.");
        repository.link(accountId,campaignId,request.goalId());
        return new Result("LINKED",accountId,campaignId,request.goalId());
    }

    @Transactional
    public Result unlink(String accountId,String campaignId) {
        checkIds(accountId,campaignId);
        if(repository.unlink(accountId,campaignId)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Nenhum vínculo encontrado.");
        return new Result("UNLINKED",accountId,campaignId,null);
    }

    private static void checkIds(String accountId,String campaignId) {
        if(accountId==null || !accountId.matches("[0-9]{1,40}") ||
                campaignId==null || !campaignId.matches("[0-9]{1,40}"))
            throw bad("Identificadores de campanha inválidos.");
    }

    private static boolean validMoney(Long value,boolean allowZero) {
        return value!=null && value>= (allowZero?0:1) && value<=LIMIT_CENTS;
    }

    private static boolean validPercent(Integer value) {
        return value!=null && value>=0 && value<=10_000;
    }

    private static long roundPercent(long cents,int basisPoints) {
        return (cents*basisPoints+5_000)/10_000;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
}
