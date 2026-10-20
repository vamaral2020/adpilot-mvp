import { NextRequest, NextResponse } from "next/server";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  // Basic auth is enforced by proxy.ts. The Origin check prevents cross-site
  // forms from initiating account connection in the authenticated browser.
  const origin = request.headers.get("origin");
  const host = request.headers.get("host");
  try {
    if (!origin || !host || new URL(origin).host !== host) {
      return NextResponse.json({ code: "INVALID_ORIGIN" }, { status: 403 });
    }
  } catch {
    return NextResponse.json({ code: "INVALID_ORIGIN" }, { status: 403 });
  }

  const username = process.env.ADPILOT_ADMIN_USERNAME ?? "";
  const password = process.env.ADPILOT_ADMIN_PASSWORD ?? "";
  const internal = process.env.INTERNAL_API_URL ?? "http://localhost:18080";
  if (!username || !password) return NextResponse.json({ code: "NOT_CONFIGURED" }, { status: 503 });

  try {
    const response = await fetch(internal + "/api/v1/nuvemshop/oauth/start", {
      headers: { Authorization: "Basic " + Buffer.from(username + ":" + password).toString("base64") },
      cache: "no-store",
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) return NextResponse.json({ code: "CONNECTION_UNAVAILABLE" }, { status: 503 });

    const data: { authorizationUrl?: string } = await response.json();
    const url = new URL(data.authorizationUrl ?? "");
    if (url.protocol !== "https:" ||
        !["www.nuvemshop.com", "www.nuvemshop.com.br", "www.tiendanube.com"].includes(url.hostname) ||
        !/^\/apps\/\d+\/authorize$/.test(url.pathname) ||
        !url.searchParams.has("state")) {
      return NextResponse.json({ code: "INVALID_AUTHORIZATION_URL" }, { status: 502 });
    }
    return NextResponse.redirect(url, { status: 303, headers: { "Cache-Control": "no-store", "Referrer-Policy": "no-referrer" } });
  } catch {
    return NextResponse.json({ code: "CONNECTION_UNAVAILABLE" }, { status: 503 });
  }
}
