/**
 * api.js — centralised API client for InfraWatch React UI.
 *
 * Priority:
 *   1. Python aggregation layer  (localhost:8090) — enriched + stats
 *   2. Quarkus backend           (localhost:8080) — raw availability
 *   3. Mock data fallback        — always works offline
 */

const AGG_BASE  = import.meta.env.VITE_AGG_URL     || "http://localhost:8090/api/agg";
const JAVA_BASE = import.meta.env.VITE_BACKEND_URL || "http://localhost:8080/api/v1";

async function fetchWithFallback(aggPath, javaPath, mockFn) {
  // Try aggregation layer first
  try {
    const r = await fetch(`${AGG_BASE}${aggPath}`, { signal: AbortSignal.timeout(4000) });
    if (r.ok) return { data: await r.json(), source: "aggregator" };
  } catch (_) { /* fall through */ }

  // Try Quarkus backend
  try {
    const r = await fetch(`${JAVA_BASE}${javaPath}`, { signal: AbortSignal.timeout(4000) });
    if (r.ok) return { data: await r.json(), source: "backend" };
  } catch (_) { /* fall through */ }

  // Local mock
  return { data: mockFn(), source: "mock" };
}

// ── Mock generators (kept from original, used as fallback) ────────────────────
const LOCATIONS_META = [
  { id: "us-east",   label: "US East",    city: "New York",      region: "Americas"     },
  { id: "us-west",   label: "US West",    city: "San Francisco", region: "Americas"     },
  { id: "eu-central",label: "EU Central", city: "Frankfurt",     region: "Europe"       },
  { id: "eu-west",   label: "EU West",    city: "London",        region: "Europe"       },
  { id: "ap-south",  label: "AP South",   city: "Mumbai",        region: "Asia Pacific" },
  { id: "ap-east",   label: "AP East",    city: "Singapore",     region: "Asia Pacific" },
  { id: "ap-north",  label: "AP North",   city: "Tokyo",         region: "Asia Pacific" },
  { id: "me-central",label: "ME Central", city: "Dubai",         region: "Middle East"  },
];
const SERVICES = ["Compute","Storage","Network","Database","Security","DNS","CDN","Messaging"];

function rand(min, max) { return +(Math.random() * (max - min) + min).toFixed(2); }
function statusFor(a)   { return a >= 99 ? "operational" : a >= 95 ? "degraded" : "critical"; }

function mockLocations() {
  return LOCATIONS_META.map(loc => {
    const services = SERVICES.map(name => {
      const availability = rand(loc.id.includes("me") ? 82 : 90, 100);
      return { name, availability, status: statusFor(availability),
               incidents: availability < 95 ? Math.floor(Math.random() * 4) : 0,
               responseTime: Math.floor(Math.random() * 280 + 20) };
    });
    const overall = +(services.reduce((a,s) => a + s.availability, 0) / services.length).toFixed(2);
    return { ...loc, locationId: loc.id, overallAvailability: overall, overall,
             status: statusFor(overall), services,
             activeIncidents: services.reduce((a,s) => a + s.incidents, 0),
             uptime30d: rand(98, 100) };
  });
}

function mockTrend() {
  return Array.from({ length: 24 }, (_, i) => ({
    time: `${String(i).padStart(2,"0")}:00`,
    availability: rand(95, 100),
    incidents: Math.floor(Math.random() * 3),
    responseTime: Math.floor(Math.random() * 150 + 50),
  }));
}

function mockSummary(locs) {
  const op = locs.filter(l => l.status === "operational").length;
  const dg = locs.filter(l => l.status === "degraded").length;
  const cr = locs.filter(l => l.status === "critical").length;
  return {
    global: {
      totalLocations: locs.length,
      globalAvailability: +(locs.reduce((a,l) => a + l.overallAvailability, 0) / locs.length).toFixed(2),
      totalActiveIncidents: locs.reduce((a,l) => a + l.activeIncidents, 0),
      statusCounts: { operational: op, degraded: dg, critical: cr },
      slaTiers: { GOLD: op, SILVER: dg, BRONZE: 0, BELOW_SLA: cr },
    }
  };
}

// ── Public API ────────────────────────────────────────────────────────────────

export async function fetchLocations() {
  const mock = mockLocations();
  const res = await fetchWithFallback("/locations", "/locations", () => mock);
  // Normalise field names (backend uses overallAvailability, mock adds overall)
  const locs = res.data.map(l => ({
    ...l,
    id:      l.locationId || l.id,
    overall: l.overallAvailability ?? l.overall ?? 0,
    services: l.services || [],
    incidents: l.activeIncidents ?? l.incidents ?? 0,
  }));
  return { locations: locs, source: res.source };
}

export async function fetchSummary(locations) {
  const res = await fetchWithFallback("/summary", "/summary", () => mockSummary(locations || mockLocations()));
  return res.data;
}

export async function fetchTrend(locationId) {
  const path = locationId ? `/trend/${locationId}` : "/trend";
  const res  = await fetchWithFallback(path, "/trend", mockTrend);
  // Aggregator returns { points, trend, ... } — flatten for chart compatibility
  const raw = res.data;
  if (Array.isArray(raw)) return raw;
  if (raw.points) return raw.points.map(p => ({
    time: p.timestamp?.slice(11, 16) || "",
    availability: p.availability,
    movingAvg: p.movingAvg,
    isAnomaly: p.isAnomaly,
  }));
  return mockTrend();
}

export async function triggerRefresh() {
  try {
    await fetch(`${JAVA_BASE}/admin/collect`, { method: "POST", signal: AbortSignal.timeout(5000) });
  } catch (_) { /* offline — ignore */ }
}
