import { NextRequest, NextResponse } from "next/server";
import { timingSafeEqual } from "node:crypto";

const matches = (actual: string, expected: string) => {
  const a = Buffer.from(actual);
  const b = Buffer.from(expected);
  return a.length === b.length && timingSafeEqual(a, b);
};

export function proxy(req: NextRequest) {
  const username = process.env.ADPILOT_ADMIN_USERNAME ?? "";
  const password = process.env.ADPILOT_ADMIN_PASSWORD ?? "";
  if (!username || password.length < 12) {
    return new NextResponse("Authentication is not configured", { status: 503 });
  }
  const header = req.headers.get("authorization") ?? "";
  const raw = /^Basic\s+([A-Za-z0-9+/=]+)$/i.exec(header)?.[1];
  let valid = false;
  if (raw && raw.length < 4096) {
    try {
      const decoded = Buffer.from(raw, "base64").toString("utf8");
      const separator = decoded.indexOf(":");
      if (separator > 0) {
        valid = matches(decoded.slice(0, separator), username)
          && matches(decoded.slice(separator + 1), password);
      }
    } catch { valid = false; }
  }
  if (!valid) {
    return new NextResponse("Authentication required", {
      status: 401,
      headers: { "WWW-Authenticate": 'Basic realm="AdPilot local"' },
    });
  }
  // Header de autorizacao entregue somente pelo ambiente ao backend server-side.
  const response = NextResponse.next();
  response.headers.set("Cache-Control", "private, no-store");
  return response;
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
