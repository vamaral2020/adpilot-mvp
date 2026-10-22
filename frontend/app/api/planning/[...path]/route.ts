import { NextRequest, NextResponse } from "next/server";

/** Whitelisted local write proxy. Never relay browser credentials to backend. */
type Context = { params: Promise<{path: string[]}> };

function upstream(): string | null {
  const username=process.env.ADPILOT_ADMIN_USERNAME??"";
  const password=process.env.ADPILOT_ADMIN_PASSWORD??"";
  if(!username || password.length<12) return null;
  return "Basic "+Buffer.from(username+":"+password).toString("base64");
}

function allowed(method:string,path:string[]):string|null {
  if(method==="GET" && path.length===1 && path[0]==="catalog")
    return "/api/v1/planning/catalog";
  if(method==="POST" && path.length===1 && path[0]==="goals")
    return "/api/v1/planning/goals";
  if(["PUT","DELETE"].includes(method) && path.length===4
      && path[0]==="campaigns" && path[3]==="goal"
      && /^[0-9]{1,40}$/.test(path[1]) && /^[0-9]{1,40}$/.test(path[2]))
    return "/api/v1/planning/campaigns/"+path[1]+"/"+path[2]+"/goal";
  return null;
}

async function handle(request:NextRequest,context:Context,method:string) {
  const {path}=await context.params;
  const destination=allowed(method,path);
  if(!destination)
    return NextResponse.json({error:"Operação não suportada."},{status:404});

  const bearer=upstream();
  if(!bearer)
    return NextResponse.json({error:"Credenciais locais indisponíveis."},{status:503});

  const isWrite=method!=="GET";
  if(isWrite) {
    const origin=request.headers.get("origin");
    // Reject cross-origin writes and missing Origin rather than weakening
    // Basic-auth cross-site request protections.
    if(origin!==new URL(request.url).origin)
      return NextResponse.json({error:"Origem inválida."},{status:403});
  }
  const base=process.env.INTERNAL_API_URL??"http://localhost:18080";
  const options:RequestInit={
    method,
    cache:"no-store",
    headers:{Authorization:bearer,Accept:"application/json"},
    signal:AbortSignal.timeout(12000),
  };
  if(method==="POST" || method==="PUT") {
    if(!(request.headers.get("content-type")??"").startsWith("application/json"))
      return NextResponse.json({error:"Envie JSON."},{status:415});
    const raw=await request.text();
    if(raw.length>4096)
      return NextResponse.json({error:"Payload excede o limite permitido."},{status:413});
    try { JSON.parse(raw); } catch {
      return NextResponse.json({error:"JSON inválido."},{status:400});
    }
    (options.headers as Record<string,string>)["Content-Type"]="application/json";
    options.body=raw;
  }
  try {
    const response=await fetch(base+destination,options);
    if(!response.ok) {
      const message=response.status===404 ? "Registro ou vínculo não encontrado."
        : response.status===400 ? "Revise os valores e confirme o vínculo."
        : "Não foi possível concluir a operação local.";
      return NextResponse.json({error:message},{status:response.status,
        headers:{"Cache-Control":"no-store"}});
    }
    const body=await response.json();
    return NextResponse.json(body,{status:response.status,
      headers:{"Cache-Control":"no-store"}});
  } catch {
    return NextResponse.json({error:"Serviço local indisponível."},
      {status:503,headers:{"Cache-Control":"no-store"}});
  }
}

export async function GET(request:NextRequest,context:Context) {
  return handle(request,context,"GET");
}
export async function POST(request:NextRequest,context:Context) {
  return handle(request,context,"POST");
}
export async function PUT(request:NextRequest,context:Context) {
  return handle(request,context,"PUT");
}
export async function DELETE(request:NextRequest,context:Context) {
  return handle(request,context,"DELETE");
}
