import { useState, useEffect, useCallback } from "react";

// ─── colour tokens ───────────────────────────────────────────────────────────
const C = {
  bg: "#0d1117",
  surface: "#161b22",
  border: "#21262d",
  accent: "#f0a500",
  accentDim: "#f0a50022",
  green: "#3fb950",
  greenDim: "#3fb95022",
  red: "#f85149",
  redDim: "#f8514922",
  blue: "#58a6ff",
  blueDim: "#58a6ff22",
  text: "#e6edf3",
  muted: "#8b949e",
  card: "#161b22",
};

const fmt = (n, d = 2) =>
  n.toLocaleString("en-CA", { minimumFractionDigits: d, maximumFractionDigits: d });

// ─── Pill ────────────────────────────────────────────────────────────────────
const Pill = ({ label, value, color = C.accent, unit = "" }) => (
  <div style={{
    background: color + "18", border: `1px solid ${color}44`,
    borderRadius: 8, padding: "10px 16px",
    display: "flex", flexDirection: "column", gap: 2, flex: 1, minWidth: 130,
  }}>
    <span style={{ color: C.muted, fontSize: 11, letterSpacing: "0.08em", textTransform: "uppercase" }}>
      {label}
    </span>
    <span style={{ color, fontSize: 22, fontWeight: 700, fontFamily: "'IBM Plex Mono', monospace" }}>
      {value}{unit && <span style={{ fontSize: 13, fontWeight: 400, marginLeft: 3 }}>{unit}</span>}
    </span>
  </div>
);

// ─── Slider / Input pair ─────────────────────────────────────────────────────
const SliderInput = ({ label, value, setValue, min, max, step, unit, color = C.accent }) => (
  <div style={{ marginBottom: 18 }}>
    <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 6 }}>
      <label style={{ color: C.muted, fontSize: 13 }}>{label}</label>
      <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
        <input
          type="number"
          value={value}
          step={step}
          min={min}
          max={max}
          onChange={e => setValue(parseFloat(e.target.value) || 0)}
          style={{
            width: 80, background: C.surface, border: `1px solid ${C.border}`,
            color: C.text, borderRadius: 6, padding: "3px 8px", fontSize: 13,
            fontFamily: "'IBM Plex Mono', monospace", textAlign: "right",
          }}
        />
        <span style={{ color: C.muted, fontSize: 12, width: 32 }}>{unit}</span>
      </div>
    </div>
    <input
      type="range" min={min} max={max} step={step} value={value}
      onChange={e => setValue(parseFloat(e.target.value))}
      style={{ width: "100%", accentColor: color, cursor: "pointer" }}
    />
  </div>
);

// ─── Section header ──────────────────────────────────────────────────────────
const SectionHead = ({ icon, title, sub }) => (
  <div style={{ borderBottom: `1px solid ${C.border}`, paddingBottom: 12, marginBottom: 20 }}>
    <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
      <span style={{ fontSize: 20 }}>{icon}</span>
      <span style={{ color: C.text, fontWeight: 700, fontSize: 16, letterSpacing: "-0.02em" }}>{title}</span>
    </div>
    {sub && <p style={{ color: C.muted, fontSize: 12, margin: "4px 0 0 30px" }}>{sub}</p>}
  </div>
);

// ─── Entry row for billing log ────────────────────────────────────────────────
const EntryRow = ({ entry, onDelete }) => {
  const diff = entry.oldCost - entry.newCost;
  return (
    <div style={{
      display: "grid", gridTemplateColumns: "90px 1fr 1fr 1fr 80px 32px",
      gap: 8, alignItems: "center",
      padding: "10px 14px", background: C.surface,
      border: `1px solid ${C.border}`, borderRadius: 8, fontSize: 13,
      marginBottom: 6,
    }}>
      <span style={{ color: C.muted, fontFamily: "'IBM Plex Mono', monospace", fontSize: 12 }}>
        {entry.date}
      </span>
      <div style={{ textAlign: "right" }}>
        <div style={{ color: C.muted, fontSize: 10 }}>Consumed</div>
        <div style={{ color: C.text, fontFamily: "'IBM Plex Mono', monospace" }}>
          {fmt(entry.consumed)} kWh
        </div>
      </div>
      <div style={{ textAlign: "right" }}>
        <div style={{ color: C.muted, fontSize: 10 }}>Exported</div>
        <div style={{ color: C.green, fontFamily: "'IBM Plex Mono', monospace" }}>
          {fmt(entry.exported)} kWh
        </div>
      </div>
      <div style={{ textAlign: "right" }}>
        <div style={{ color: C.muted, fontSize: 10 }}>Peak kW</div>
        <div style={{ color: C.accent, fontFamily: "'IBM Plex Mono', monospace" }}>
          {fmt(entry.peakKw, 1)} kW
        </div>
      </div>
      <div style={{ textAlign: "right" }}>
        <div style={{ color: C.muted, fontSize: 10 }}>Δ Cost</div>
        <div style={{ color: diff >= 0 ? C.green : C.red, fontWeight: 700, fontFamily: "'IBM Plex Mono', monospace", fontSize: 12 }}>
          {diff >= 0 ? "−" : "+"} ${Math.abs(diff).toFixed(2)}
        </div>
      </div>
      <button onClick={onDelete}
        style={{ background: "none", border: "none", color: C.red, cursor: "pointer", fontSize: 16, padding: 0 }}>
        ×
      </button>
    </div>
  );
};

// ═══════════════════════════════════════════════════════════════════════════════
// MAIN COMPONENT
// ═══════════════════════════════════════════════════════════════════════════════
export default function NBPowerSolarTracker() {
  // ── Rate settings ────────────────────────────────────────────────────────
  const [currentRate, setCurrentRate] = useState(14.76);   // ¢/kWh current net metering
  const [importRate,  setImportRate]  = useState(14.76);   // ¢/kWh new: grid import
  const [exportRate,  setExportRate]  = useState(6.77);    // ¢/kWh new: solar export credit
  const [demandCharge, setDemandCharge] = useState(13.00); // $/kW peak demand charge

  // ── Scenario inputs ───────────────────────────────────────────────────────
  const [consumed, setConsumed] = useState(500);   // kWh pulled from grid
  const [exported, setExported] = useState(300);   // kWh sent to grid
  const [peakKw,   setPeakKw]  = useState(8.4);   // kW peak 15-min demand

  // ── Billing log ───────────────────────────────────────────────────────────
  const [log, setLog] = useState(() => {
    try {
      const s = localStorage.getItem("nbp_solar_log");
      return s ? JSON.parse(s) : [];
    } catch { return []; }
  });

  const [tab, setTab] = useState("calc"); // "calc" | "log" | "rates" | "guide"

  // persist log
  useEffect(() => {
    try { localStorage.setItem("nbp_solar_log", JSON.stringify(log)); } catch {}
  }, [log]);

  // ── Calculations ──────────────────────────────────────────────────────────
  const currentNetKwh = consumed - exported; // positive = owe money, negative = credit
  const oldCost = currentNetKwh * (currentRate / 100);

  // New model
  const newImportCost  = consumed  * (importRate  / 100);
  const newExportCredit = exported * (exportRate  / 100);
  const newDemandCost  = peakKw   * demandCharge;
  const newCost = newImportCost - newExportCredit + newDemandCost;

  const delta = newCost - oldCost; // positive = new model costs more
  const deltaLabel = delta >= 0 ? `+$${fmt(delta)}` : `-$${fmt(Math.abs(delta))}`;

  const addEntry = () => {
    const entry = {
      id: Date.now(),
      date: new Date().toISOString().split("T")[0],
      consumed, exported, peakKw,
      oldCost, newCost,
    };
    setLog(prev => [entry, ...prev].slice(0, 24));
  };

  const deleteEntry = id => setLog(prev => prev.filter(e => e.id !== id));

  // ── Summary stats from log ────────────────────────────────────────────────
  const logSavings = log.reduce((acc, e) => acc + (e.oldCost - e.newCost), 0);
  const avgPeak = log.length ? log.reduce((a, e) => a + e.peakKw, 0) / log.length : 0;

  // ── Tab bar ───────────────────────────────────────────────────────────────
  const tabs = [
    { id: "calc",  label: "Calculator" },
    { id: "log",   label: `Billing Log${log.length ? ` (${log.length})` : ""}` },
    { id: "rates", label: "Rate Settings" },
    { id: "guide", label: "Integration Guide" },
  ];

  const cardStyle = {
    background: C.card, border: `1px solid ${C.border}`,
    borderRadius: 12, padding: "22px 22px",
    marginBottom: 16,
  };

  return (
    <div style={{
      background: C.bg, minHeight: "100vh", color: C.text,
      fontFamily: "'IBM Plex Sans', 'Segoe UI', sans-serif",
      padding: "0 0 60px",
    }}>
      {/* ── Header ── */}
      <div style={{
        background: `linear-gradient(135deg, #0d1117 0%, #161b22 100%)`,
        borderBottom: `1px solid ${C.border}`,
        padding: "28px 24px 20px",
      }}>
        <div style={{ maxWidth: 760, margin: "0 auto" }}>
          <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 4 }}>
            <span style={{ fontSize: 28 }}>☀️</span>
            <div>
              <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, letterSpacing: "-0.04em", color: C.text }}>
                NB Power Solar Impact Tracker
              </h1>
              <p style={{ margin: 0, fontSize: 12, color: C.muted, marginTop: 2 }}>
                Compare current net metering vs. proposed rate structure · Pending EUB approval Apr 2027
              </p>
            </div>
          </div>

          {/* quick diff badge */}
          <div style={{
            marginTop: 14, display: "inline-flex", alignItems: "center", gap: 8,
            background: delta > 0 ? C.redDim : C.greenDim,
            border: `1px solid ${delta > 0 ? C.red : C.green}55`,
            borderRadius: 20, padding: "5px 14px",
          }}>
            <span style={{ fontSize: 18 }}>{delta > 0 ? "⚠️" : "✅"}</span>
            <span style={{ fontSize: 13, color: delta > 0 ? C.red : C.green, fontWeight: 600 }}>
              New model is <strong>{deltaLabel}</strong> vs. current net metering for these inputs
            </span>
          </div>
        </div>
      </div>

      {/* ── Tab bar ── */}
      <div style={{ borderBottom: `1px solid ${C.border}`, background: C.surface }}>
        <div style={{ maxWidth: 760, margin: "0 auto", display: "flex", gap: 0 }}>
          {tabs.map(t => (
            <button key={t.id} onClick={() => setTab(t.id)} style={{
              background: "none", border: "none", cursor: "pointer",
              padding: "13px 18px",
              color: tab === t.id ? C.accent : C.muted,
              fontWeight: tab === t.id ? 700 : 400,
              fontSize: 13,
              borderBottom: tab === t.id ? `2px solid ${C.accent}` : "2px solid transparent",
              transition: "color 0.15s",
            }}>
              {t.label}
            </button>
          ))}
        </div>
      </div>

      <div style={{ maxWidth: 760, margin: "0 auto", padding: "20px 16px" }}>

        {/* ══════════ CALCULATOR TAB ══════════ */}
        {tab === "calc" && (<>
          {/* Inputs */}
          <div style={cardStyle}>
            <SectionHead icon="📊" title="Billing Period Inputs"
              sub="Enter your usage for this billing cycle" />

            <SliderInput label="Energy consumed from grid" value={consumed}
              setValue={setConsumed} min={0} max={2000} step={10} unit="kWh" color={C.blue} />
            <SliderInput label="Energy exported to grid (solar)" value={exported}
              setValue={setExported} min={0} max={1500} step={10} unit="kWh" color={C.green} />
            <SliderInput label="Highest 15-min demand spike (÷ 4 for kW)"
              value={peakKw} setValue={setPeakKw} min={0} max={30} step={0.1} unit="kW" color={C.accent} />

            <div style={{
              background: C.accentDim, border: `1px solid ${C.accent}33`,
              borderRadius: 8, padding: "10px 14px", fontSize: 12, color: C.muted, marginTop: 4,
            }}>
              💡 <strong style={{ color: C.accent }}>Peak demand tip:</strong> Your Emporia Vue records power every second.
              The proposed charge uses the highest 15-minute average window (in watts ÷ 1000 = kW).
              NB Power's example used 8.4 kW.
            </div>
          </div>

          {/* Side-by-side cost cards */}
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12, marginBottom: 16 }}>
            {/* Current */}
            <div style={{ ...cardStyle, marginBottom: 0, border: `1px solid ${C.blue}44` }}>
              <div style={{ color: C.blue, fontWeight: 700, marginBottom: 14, fontSize: 14 }}>
                📋 Current Net Metering
              </div>
              <div style={{ fontSize: 12, color: C.muted, marginBottom: 8 }}>
                Net kWh: <span style={{ color: C.text, fontFamily: "'IBM Plex Mono', monospace" }}>
                  {currentNetKwh > 0 ? `+${fmt(currentNetKwh)}` : fmt(currentNetKwh)} kWh
                </span>
              </div>
              <div style={{ fontSize: 12, color: C.muted, marginBottom: 12 }}>
                Rate: <span style={{ color: C.text, fontFamily: "'IBM Plex Mono', monospace" }}>
                  {fmt(currentRate, 2)}¢/kWh (1:1 offset)
                </span>
              </div>
              <div style={{
                fontSize: 32, fontWeight: 800, color: oldCost > 0 ? C.red : C.green,
                fontFamily: "'IBM Plex Mono', monospace", letterSpacing: "-0.03em",
              }}>
                {oldCost >= 0 ? "" : "−"}${Math.abs(oldCost).toFixed(2)}
              </div>
              <div style={{ fontSize: 11, color: C.muted, marginTop: 2 }}>
                {oldCost < 0 ? "credit carried forward" : "you owe"}
              </div>
            </div>

            {/* Proposed */}
            <div style={{ ...cardStyle, marginBottom: 0, border: `1px solid ${C.accent}44` }}>
              <div style={{ color: C.accent, fontWeight: 700, marginBottom: 14, fontSize: 14 }}>
                🔀 Proposed Structure
              </div>
              <div style={{ fontSize: 11, color: C.muted, marginBottom: 4 }}>
                Import: <span style={{ color: C.red, fontFamily: "'IBM Plex Mono', monospace" }}>
                  +${(consumed * importRate / 100).toFixed(2)}
                </span>
              </div>
              <div style={{ fontSize: 11, color: C.muted, marginBottom: 4 }}>
                Export credit: <span style={{ color: C.green, fontFamily: "'IBM Plex Mono', monospace" }}>
                  −${(exported * exportRate / 100).toFixed(2)}
                </span>
              </div>
              <div style={{ fontSize: 11, color: C.muted, marginBottom: 10 }}>
                Demand charge: <span style={{ color: C.accent, fontFamily: "'IBM Plex Mono', monospace" }}>
                  +${newDemandCost.toFixed(2)}
                </span>
                <span style={{ color: C.muted }}> ({fmt(peakKw, 1)} kW × ${demandCharge})</span>
              </div>
              <div style={{
                fontSize: 32, fontWeight: 800, color: newCost > 0 ? C.red : C.green,
                fontFamily: "'IBM Plex Mono', monospace", letterSpacing: "-0.03em",
              }}>
                {newCost >= 0 ? "" : "−"}${Math.abs(newCost).toFixed(2)}
              </div>
              <div style={{ fontSize: 11, color: C.muted, marginTop: 2 }}>
                {newCost < 0 ? "credit carried forward" : "you owe"}
              </div>
            </div>
          </div>

          {/* Delta bar */}
          <div style={{
            ...cardStyle,
            background: delta > 0 ? C.redDim : C.greenDim,
            border: `1px solid ${delta > 0 ? C.red : C.green}44`,
            display: "flex", flexWrap: "wrap", gap: 10, alignItems: "center",
            justifyContent: "space-between",
          }}>
            <div>
              <div style={{ fontSize: 12, color: C.muted }}>Monthly impact of proposed change</div>
              <div style={{ fontSize: 26, fontWeight: 800, color: delta > 0 ? C.red : C.green,
                fontFamily: "'IBM Plex Mono', monospace" }}>
                {delta >= 0 ? "+" : "−"}${Math.abs(delta).toFixed(2)} / month
              </div>
              <div style={{ fontSize: 11, color: C.muted }}>
                {delta > 0 ? "extra cost under proposed structure" : "saving under proposed structure"}
                {" · "}~${(Math.abs(delta) * 12).toFixed(0)}/yr
              </div>
            </div>
            <button onClick={addEntry} style={{
              background: C.accent, color: "#000", border: "none",
              borderRadius: 8, padding: "10px 20px", fontWeight: 700,
              fontSize: 13, cursor: "pointer", letterSpacing: "0.02em",
            }}>
              💾 Save to Log
            </button>
          </div>

          {/* Demand charge explainer */}
          <div style={{ ...cardStyle, background: "#0d1117", border: `1px solid ${C.accent}33` }}>
            <SectionHead icon="⚡" title="Understanding the Demand Charge"
              sub="The most unpredictable part of the proposed structure" />
            <p style={{ fontSize: 13, color: C.muted, lineHeight: 1.7, margin: 0 }}>
              The proposed <strong style={{ color: C.accent }}>$13/kW demand charge</strong> applies to your
              highest 15-minute usage window during the billing period, between 7 am–10 pm.
              This is a <em>single spike</em> — running your oven, dryer, and EV charger simultaneously
              for just 15 minutes could define your entire month's demand charge.
              At 8.4 kW that's <strong style={{ color: C.red }}>${(8.4 * 13).toFixed(2)}</strong>;
              at 12 kW it's <strong style={{ color: C.red }}>${(12 * 13).toFixed(2)}</strong>.
            </p>
            <div style={{
              display: "flex", flexWrap: "wrap", gap: 8, marginTop: 14,
            }}>
              {[
                ["4 kW (small spike)", 4],
                ["8.4 kW (NB Power example)", 8.4],
                ["12 kW (heavy usage)", 12],
                ["16 kW (heat pump + EV)", 16],
              ].map(([label, kw]) => (
                <div key={kw} onClick={() => setPeakKw(kw)} style={{
                  background: C.surface, border: `1px solid ${C.border}`,
                  borderRadius: 6, padding: "6px 12px", cursor: "pointer",
                  fontSize: 12, color: C.muted,
                  transition: "border-color 0.15s",
                }}>
                  {label} → <span style={{ color: C.accent, fontFamily: "'IBM Plex Mono', monospace" }}>
                    ${(kw * demandCharge).toFixed(2)}
                  </span>
                </div>
              ))}
            </div>
          </div>
        </>)}

        {/* ══════════ LOG TAB ══════════ */}
        {tab === "log" && (<>
          {log.length > 0 && (
            <div style={{ display: "flex", flexWrap: "wrap", gap: 10, marginBottom: 16 }}>
              <Pill label="Months logged" value={log.length} />
              <Pill label="Total extra cost" value={`${logSavings >= 0 ? "−" : "+"}$${Math.abs(logSavings).toFixed(2)}`}
                color={logSavings >= 0 ? C.green : C.red} />
              <Pill label="Avg peak demand" value={`${fmt(avgPeak, 1)}`} unit="kW" color={C.accent} />
            </div>
          )}

          <div style={cardStyle}>
            <SectionHead icon="📅" title="Billing Period Log"
              sub="Save calculator results each month to track actuals vs. projections" />
            {log.length === 0 ? (
              <div style={{
                textAlign: "center", padding: "40px 0", color: C.muted, fontSize: 13,
              }}>
                No entries yet. Use the Calculator tab and click "Save to Log" each billing period.
              </div>
            ) : (
              <>
                <div style={{
                  display: "grid",
                  gridTemplateColumns: "90px 1fr 1fr 1fr 80px 32px",
                  gap: 8, padding: "6px 14px",
                  fontSize: 10, color: C.muted, textTransform: "uppercase", letterSpacing: "0.06em",
                  marginBottom: 4,
                }}>
                  <span>Date</span>
                  <span style={{ textAlign: "right" }}>Consumed</span>
                  <span style={{ textAlign: "right" }}>Exported</span>
                  <span style={{ textAlign: "right" }}>Peak</span>
                  <span style={{ textAlign: "right" }}>Δ Cost</span>
                  <span />
                </div>
                {log.map(e => (
                  <EntryRow key={e.id} entry={e} onDelete={() => deleteEntry(e.id)} />
                ))}
              </>
            )}
          </div>
        </>)}

        {/* ══════════ RATES TAB ══════════ */}
        {tab === "rates" && (
          <div style={cardStyle}>
            <SectionHead icon="⚙️" title="Rate Configuration"
              sub="Adjust as NB Power's proposed rates are confirmed by the EUB" />

            <div style={{
              background: C.blueDim, border: `1px solid ${C.blue}33`,
              borderRadius: 8, padding: "12px 16px", marginBottom: 20, fontSize: 13, color: C.muted,
            }}>
              📌 <strong style={{ color: C.blue }}>Current NB Power net metering rate:</strong> 14.76¢/kWh (1:1 offset).
              Proposed changes pending EUB approval — new rates targeted for <strong style={{ color: C.blue }}>April 1, 2027</strong>.
            </div>

            <SliderInput label="Current net metering rate (both import & export)" value={currentRate}
              setValue={setCurrentRate} min={5} max={25} step={0.01} unit="¢/kWh" color={C.blue} />

            <div style={{ borderTop: `1px solid ${C.border}`, margin: "20px 0" }} />
            <p style={{ color: C.muted, fontSize: 12, marginTop: 0 }}>Proposed structure rates:</p>

            <SliderInput label="New import rate (grid → home)" value={importRate}
              setValue={setImportRate} min={5} max={30} step={0.01} unit="¢/kWh" color={C.red} />
            <SliderInput label="New export credit rate (solar → grid)" value={exportRate}
              setValue={setExportRate} min={1} max={20} step={0.01} unit="¢/kWh" color={C.green} />
            <SliderInput label="Demand charge rate" value={demandCharge}
              setValue={setDemandCharge} min={0} max={30} step={0.25} unit="$/kW" color={C.accent} />

            <div style={{
              display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10, marginTop: 16,
            }}>
              <div style={{
                background: C.surface, border: `1px solid ${C.border}`,
                borderRadius: 8, padding: "12px 14px", fontSize: 12,
              }}>
                <div style={{ color: C.muted, marginBottom: 4 }}>Export-to-import ratio</div>
                <div style={{ color: C.accent, fontFamily: "'IBM Plex Mono', monospace", fontSize: 18 }}>
                  {(exportRate / importRate * 100).toFixed(0)}%
                </div>
                <div style={{ color: C.muted, fontSize: 11 }}>
                  You get {(exportRate / importRate * 100).toFixed(0)}¢ back for every dollar you send
                </div>
              </div>
              <div style={{
                background: C.surface, border: `1px solid ${C.border}`,
                borderRadius: 8, padding: "12px 14px", fontSize: 12,
              }}>
                <div style={{ color: C.muted, marginBottom: 4 }}>Break-even peak demand</div>
                <div style={{ color: C.accent, fontFamily: "'IBM Plex Mono', monospace", fontSize: 18 }}>
                  0 kW
                </div>
                <div style={{ color: C.muted, fontSize: 11 }}>
                  Any demand spike adds cost — there's no threshold
                </div>
              </div>
            </div>
          </div>
        )}

        {/* ══════════ GUIDE TAB ══════════ */}
        {tab === "guide" && (<>
          <div style={cardStyle}>
            <SectionHead icon="🔌" title="Emporia Vue + Hubitat Integration"
              sub="How to get the right data into this tracker" />

            <div style={{
              display: "flex", flexDirection: "column", gap: 12,
            }}>
              {[
                {
                  step: "1",
                  title: "Emporia Vue data you need",
                  color: C.blue,
                  content: `Your Emporia Vue Energy Monitor tracks power flow in real time. For this tracker you need three values per billing period:\n\n• Total kWh consumed from the grid (mains CT, minus solar self-consumption)\n• Total kWh exported to the grid (solar CT channel)\n• Peak 15-minute average demand (kW) — this is the trickiest one`,
                },
                {
                  step: "2",
                  title: "Hubitat EmporiaVue Integration",
                  color: C.green,
                  content: `A community driver exists for Hubitat:\n\ngithub.com/amithalp/EmporiaV2\n\nInstall via Hubitat Package Manager or manually. It uses Emporia's cloud API with your account credentials to pull energy data into Hubitat as virtual devices. You can then use Hubitat's built-in energy reporting or Rule Machine to log kWh totals.`,
                },
                {
                  step: "3",
                  title: "Tracking peak 15-minute demand",
                  color: C.accent,
                  content: `This is the critical value for the demand charge. Options:\n\n• Emporia's own app shows power in real time — watch for spikes\n• Use Hubitat Rule Machine: poll the Emporia device's "power" attribute (in watts) every minute, keep a running max, reset monthly\n• The Emporia Vue samples every second internally; 15-min average = sum of 15 minutes of readings ÷ 900\n• Divide watts by 1000 to get kW, then multiply by $13`,
                },
                {
                  step: "4",
                  title: "Monthly workflow",
                  color: C.muted,
                  content: `At the end of each billing period:\n1. Note total kWh imported (grid → home)\n2. Note total kWh exported (solar → grid)\n3. Note peak 15-min kW demand\n4. Enter those values in the Calculator tab\n5. Click "Save to Log" to record the month\n\nThe log tab will show you your running total of how the new structure would compare, month by month.`,
                },
                {
                  step: "5",
                  title: "Reducing your demand charge",
                  color: C.red,
                  content: `Since a single 15-minute spike sets your entire month's demand charge:\n\n• Avoid running EV charger + dryer + oven simultaneously\n• Schedule heavy loads sequentially, not concurrently\n• Your Emporia app can alert you when power exceeds a threshold\n• In Hubitat, use Rule Machine to notify when instantaneous watts > your target\n• Consider shifting EV charging to off-peak hours`,
                },
              ].map(({ step, title, color, content }) => (
                <div key={step} style={{
                  background: C.bg, border: `1px solid ${C.border}`,
                  borderRadius: 10, padding: "16px 18px",
                  borderLeft: `3px solid ${color}`,
                }}>
                  <div style={{ display: "flex", gap: 10, alignItems: "center", marginBottom: 8 }}>
                    <div style={{
                      width: 22, height: 22, borderRadius: "50%",
                      background: color + "22", border: `1px solid ${color}55`,
                      display: "flex", alignItems: "center", justifyContent: "center",
                      fontSize: 11, fontWeight: 700, color,
                    }}>{step}</div>
                    <span style={{ fontWeight: 700, fontSize: 14, color: C.text }}>{title}</span>
                  </div>
                  <p style={{
                    margin: 0, fontSize: 12, color: C.muted, lineHeight: 1.8,
                    whiteSpace: "pre-line",
                  }}>{content}</p>
                </div>
              ))}
            </div>
          </div>

          <div style={{
            ...cardStyle,
            background: C.accentDim,
            border: `1px solid ${C.accent}44`,
          }}>
            <div style={{ fontWeight: 700, color: C.accent, marginBottom: 8 }}>
              ⚖️ Regulatory status
            </div>
            <p style={{ fontSize: 13, color: C.muted, margin: 0, lineHeight: 1.7 }}>
              The proposed changes are part of NB Power's General Rate Application before the
              Energy and Utilities Board (EUB). If approved, they would take effect
              <strong style={{ color: C.text }}> April 1, 2027</strong>. The EUB process includes
              public hearings and intervenor participation — Solar NB Solaire and others
              are actively opposing the demand charge. Keep an eye on EUB docket proceedings
              at <strong style={{ color: C.accent }}>nbeub.ca</strong>.
            </p>
          </div>
        </>)}

      </div>
    </div>
  );
}
