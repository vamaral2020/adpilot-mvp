/**
 * UTC is the current AdPilot date-boundary convention for delivery diagnostics.
 * Meta's reporting timezone can differ; the UI explicitly cautions about that.
 * Today is allowed for monitoring, but is never a finalized observation.
 */
export function utcToday(now: Date = new Date()): string {
  return now.toISOString().slice(0,10);
}

function calendarDate(value: string): boolean {
  if(!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed=new Date(value+"T00:00:00.000Z");
  return !Number.isNaN(parsed.getTime())&&parsed.toISOString().slice(0,10)===value;
}

export type InterpreterPeriod = {
  valid: boolean;
  partialCurrentDay: boolean;
  mode: "CURRENT_DAY_MONITORING" | "HISTORICAL_CONTEXT" | "INVALID";
};

export function interpreterPeriod(
  since:string, until:string, today:string=utcToday(),
):InterpreterPeriod {
  if(!calendarDate(since)||!calendarDate(until)||!calendarDate(today)
      ||since>until||until>today) {
    return {valid:false,partialCurrentDay:false,mode:"INVALID"};
  }
  const days=(Date.parse(until+"T00:00:00.000Z")-
    Date.parse(since+"T00:00:00.000Z"))/86400000;
  if(days<0||days>30) return {valid:false,partialCurrentDay:false,mode:"INVALID"};
  const partialCurrentDay=until===today;
  return {valid:true,partialCurrentDay,
    mode:partialCurrentDay?"CURRENT_DAY_MONITORING":"HISTORICAL_CONTEXT"};
}
