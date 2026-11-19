/**
 * LUX-Ω (v5.4) — Módulo Frontend Multi-Camada Iceberg (OSINT / Surface / Deep / Darknet)
 * Integra:
 *  - Aba Temática Dedicada (osint / iceberg) na barra superior e árvore lateral
 *  - Painel Config -> Seção 2B (Endpoints, Download lux_iceberg_bridge.py, Diagnóstico Proxies)
 *  - Ferramentas Autônomas <tool> em até 3 etapas encadeadas (iceberg_search / osint_inspect)
 *  - Comandos Diretos no Chat (pesquisar iceberg, buscar osint, inspecionar url, acessar onion/i2p)
 */
(function () {
  "use strict";

  const STORAGE_KEYS = {
    BRIDGE_URL: "lux_v54_iceberg_bridge_url",
    SEARXNG_URL: "lux_v54_iceberg_searxng_url",
    ACTIVE_THEME: "lux_v54_active_theme",
    LOGS: "lux_v54_iceberg_logs",
  };

  const state = {
    bridgeUrl: localStorage.getItem(STORAGE_KEYS.BRIDGE_URL) || "http://127.0.0.1:8765",
    searxngUrl: localStorage.getItem(STORAGE_KEYS.SEARXNG_URL) || "http://127.0.0.1:8888",
    icebergMode: localStorage.getItem(STORAGE_KEYS.ACTIVE_THEME) === "osint",
    toolChainDepth: 0,
    maxToolSteps: 3,
  };

  function logToLuxDB(level, message, details = null) {
    const entry = {
      ts: new Date().toISOString(),
      module: "ICEBERG-OSINT",
      level,
      message,
      details,
    };
    console.log(`[LUX-Ω ${entry.module}] ${level}: ${message}`, details || "");

    try {
      const logs = JSON.parse(localStorage.getItem(STORAGE_KEYS.LOGS) || "[]");
      logs.unshift(entry);
      localStorage.setItem(STORAGE_KEYS.LOGS, JSON.stringify(logs.slice(0, 100)));
    } catch (_) {}

    const logContainer =
      document.querySelector("#logs-db-output, #logs-container, [data-logs-panel], .logs-db-list");
    if (logContainer) {
      const line = document.createElement("div");
      line.style.cssText =
        "font-family:monospace;font-size:12px;padding:4px 8px;border-bottom:1px solid rgba(255,255,255,0.08);color:#a5f3fc;";
      line.textContent = `[${entry.ts.slice(11, 19)}] [${level}] ${message}${
        details ? " → " + JSON.stringify(details) : ""
      }`;
      logContainer.prepend(line);
    }
  }

  async function callBridge(path, options = {}) {
    const url = state.bridgeUrl.replace(/\/+$/, "") + path;
    const res = await fetch(url, {
      ...options,
      headers: {
        "Content-Type": "application/json",
        ...(options.headers || {}),
      },
    });
    return await res.json();
  }

  async function testProxies() {
    logToLuxDB("INFO", "Iniciando diagnóstico Tor / I2Pd / SearXNG...");
    const badgeBridge = document.getElementById("lux-st-bridge");
    const badgeTor = document.getElementById("lux-st-tor");
    const badgeI2p = document.getElementById("lux-st-i2p");
    const badgeSearx = document.getElementById("lux-st-searx");

    try {
      const data = await callBridge(`/health?searxng=${encodeURIComponent(state.searxngUrl)}`, {
        method: "GET",
      });
      const setBadge = (el, status, extra = "") => {
        if (!el) return;
        const isOn = status === "ON";
        el.textContent = `${status}${extra ? " (" + extra + ")" : ""}`;
        el.style.background = isOn ? "#065f46" : "#7f1d1d";
        el.style.color = isOn ? "#6ee7b7" : "#fca5a5";
      };

      setBadge(badgeBridge, data.bridge?.status || "OFF");
      setBadge(
        badgeTor,
        data.proxies?.tor_socks5h?.status || "OFF",
        data.proxies?.tor_socks5h?.latency_ms ? `${data.proxies.tor_socks5h.latency_ms}ms` : ""
      );
      setBadge(
        badgeI2p,
        data.proxies?.i2pd_http?.status || "OFF",
        data.proxies?.i2pd_http?.latency_ms ? `${data.proxies.i2pd_http.latency_ms}ms` : ""
      );
      setBadge(
        badgeSearx,
        data.proxies?.searxng?.status || "OFF",
        data.proxies?.searxng?.latency_ms ? `${data.proxies.searxng.latency_ms}ms` : ""
      );

      logToLuxDB("SUCCESS", "Diagnóstico de camadas concluído", data.proxies);
      return data;
    } catch (err) {
      [badgeBridge, badgeTor, badgeI2p, badgeSearx].forEach((el) => {
        if (el) {
          el.textContent = "OFF";
          el.style.background = "#7f1d1d";
          el.style.color = "#fca5a5";
        }
      });
      logToLuxDB("ERROR", "Falha ao conectar na ponte local lux_iceberg_bridge.py", {
        endpoint: state.bridgeUrl,
        error: err.message,
      });
      return null;
    }
  }

  async function downloadBridgeScript() {
    logToLuxDB("INFO", "Solicitando download de lux_iceberg_bridge.py...");
    try {
      const res = await fetch(state.bridgeUrl.replace(/\/+$/, "") + "/download/bridge");
      if (!res.ok) throw new Error("Bridge offline");
      const blob = await res.blob();
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = "lux_iceberg_bridge.py";
      a.click();
      logToLuxDB("SUCCESS", "lux_iceberg_bridge.py baixado diretamente da ponte local.");
    } catch (_) {
      alert(
        "A ponte local ainda não está ativa em " +
          state.bridgeUrl +
          ". Execute o arquivo lux_iceberg_bridge.py que está na pasta do projeto LUX-Ω."
      );
    }
  }

  function renderResultCard(title, htmlContent) {
    const chatContainer =
      document.querySelector("#chat-messages, #messages, .chat-history, main") || document.body;
    const card = document.createElement("div");
    card.className = "lux-iceberg-card";
    card.style.cssText =
      "margin:12px auto;max-width:860px;background:#0d1422;border:1px solid #1e3a5f;border-left:4px solid #06b6d4;border-radius:10px;padding:14px 16px;color:#e2e8f0;font-family:system-ui,-apple-system,sans-serif;box-shadow:0 8px 24px rgba(0,0,0,0.35);";
    card.innerHTML = `
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
        <strong style="color:#22d3ee;font-size:13px;letter-spacing:0.4px;">🧊 LUX-Ω ICEBERG OSINT — ${title}</strong>
        <span style="font-size:11px;color:#94a3b8;">v5.4 Multi-Layer</span>
      </div>
      <div style="font-size:13px;line-height:1.55;">${htmlContent}</div>
    `;
    chatContainer.appendChild(card);
    card.scrollIntoView({ behavior: "smooth", block: "end" });
  }

  async function executeIcebergSearch(query) {
    logToLuxDB("TOOL", `Executando iceberg_search: "${query}"`);
    const data = await callBridge("/search", {
      method: "POST",
      body: JSON.stringify({ query, searxng_url: state.searxngUrl }),
    });
    if (!data.ok) {
      renderResultCard("Erro na Busca", `<span style="color:#f87171;">${data.error}</span>`);
      return data;
    }

    let sectionsHtml = `<div style="margin-bottom:8px;color:#94a3b8;">Consulta: <b>${query}</b> • ${data.total_results} resultados consolidados em ${data.elapsed_ms}ms</div>`;
    for (const [layerName, items] of Object.entries(data.by_layer || {})) {
      if (!items || items.length === 0) continue;
      sectionsHtml += `<div style="margin-top:10px;"><div style="color:#38bdf8;font-weight:600;margin-bottom:4px;">▸ ${layerName} (${items.length})</div><ul style="margin:0;padding-left:18px;">`;
      for (const it of items.slice(0, 6)) {
        sectionsHtml += `<li style="margin-bottom:6px;"><a href="${it.url}" target="_blank" rel="noopener" style="color:#67e8f9;text-decoration:none;font-weight:500;">${it.title}</a> <span style="font-size:11px;color:#64748b;">[${it.source}]</span><br><span style="color:#cbd5e1;font-size:12px;">${it.snippet || ""}</span></li>`;
      }
      sectionsHtml += `</ul></div>`;
    }
    renderResultCard(`Varredura Multi-Camada`, sectionsHtml);
    return data;
  }

  async function executeOsintInspect(targetUrl) {
    logToLuxDB("TOOL", `Executando osint_inspect: "${targetUrl}"`);
    const data = await callBridge("/inspect", {
      method: "POST",
      body: JSON.stringify({ url: targetUrl, use_playwright: true }),
    });
    if (!data.ok) {
      renderResultCard(
        "Falha na Inspeção OSINT",
        `<div><b>Alvo:</b> ${targetUrl}</div><div><b>Rota:</b> ${data.route}</div><div style="color:#f87171;margin-top:4px;">${data.error}</div>`
      );
      return data;
    }

    const darkLinksHtml =
      data.darknet_links_found && data.darknet_links_found.length
        ? `<div style="margin-top:8px;"><b style="color:#f59e0b;">Links Ocultos Detectados (.onion/.i2p):</b><br><code style="font-size:11px;color:#fcd34d;">${data.darknet_links_found.join("<br>")}</code></div>`
        : "";

    const bodyHtml = `
      <div><b>Alvo:</b> ${data.url}</div>
      <div><b>Túnel / Rota:</b> <span style="color:#34d399;">${data.route}</span> • <b>HTTP:</b> ${data.status_code}</div>
      <div><b>Título:</b> ${data.title || "(sem título)"}</div>
      ${darkLinksHtml}
      <details style="margin-top:8px;">
        <summary style="cursor:pointer;color:#38bdf8;">Cabeçalhos HTTP & Metadados</summary>
        <pre style="background:#070b14;padding:8px;border-radius:6px;overflow:auto;font-size:11px;max-height:160px;">${JSON.stringify(
          { headers: data.headers, meta: data.meta },
          null,
          2
        )}</pre>
      </details>
      <div style="margin-top:8px;background:#070b14;padding:10px;border-radius:6px;max-height:220px;overflow:auto;font-size:12px;color:#cbd5e1;white-space:pre-wrap;">${(
        data.clean_text || ""
      ).slice(0, 2200)}</div>
    `;
    renderResultCard("Inspeção Profunda de Alvo", bodyHtml);
    return data;
  }

  async function handleDirectChatCommand(rawText) {
    const text = (rawText || "").trim();
    const searchMatch = text.match(/^(?:pesquisar\s+iceberg|buscar\s+osint)\s+(.+)$/i);
    if (searchMatch) {
      await executeIcebergSearch(searchMatch[1].trim());
      return true;
    }

    const inspectMatch = text.match(
      /^(?:inspecionar\s+url|acessar\s+onion|acessar\s+i2p)\s+(\S+)$/i
    );
    if (inspectMatch) {
      await executeOsintInspect(inspectMatch[1].trim());
      return true;
    }
    return false;
  }

  async function processAutonomousTools(assistantText) {
    if (state.toolChainDepth >= state.maxToolSteps) {
      logToLuxDB("WARN", "Limite máximo de 3 etapas encadeadas <tool> atingido.");
      state.toolChainDepth = 0;
      return;
    }
    const toolMatch = assistantText.match(/<tool>([\s\S]*?)<\/tool>/i);
    if (!toolMatch) {
      state.toolChainDepth = 0;
      return;
    }

    try {
      const payload = JSON.parse(toolMatch[1].trim());
      state.toolChainDepth += 1;
      logToLuxDB("AUTO-TOOL", `Etapa ${state.toolChainDepth}/${state.maxToolSteps}: ${payload.name}`);

      let toolResult = null;
      if (payload.name === "iceberg_search") {
        toolResult = await executeIcebergSearch(payload.query || payload.q || "");
      } else if (payload.name === "osint_inspect") {
        toolResult = await executeOsintInspect(payload.url || payload.target || "");
      }

      if (toolResult && typeof window.sendLuxMessage === "function") {
        window.sendLuxMessage(
          `<tool_result name="${payload.name}" step="${state.toolChainDepth}">\n${JSON.stringify(
            toolResult
          ).slice(0, 6000)}\n</tool_result>`
        );
      }
    } catch (err) {
      logToLuxDB("ERROR", "Erro ao interpretar bloco <tool>", { error: err.message });
      state.toolChainDepth = 0;
    }
  }

  function injectUI() {
    if (document.getElementById("lux-iceberg-v54-dock")) return;

    const dock = document.createElement("div");
    dock.id = "lux-iceberg-v54-dock";
    dock.style.cssText =
      "position:fixed;bottom:14px;right:14px;z-index:99999;font-family:system-ui,-apple-system,sans-serif;";

    dock.innerHTML = `
      <div style="display:flex;gap:6px;align-items:center;background:#0b1220;border:1px solid #1e3a5f;padding:6px 10px;border-radius:999px;box-shadow:0 6px 20px rgba(0,0,0,0.45);">
        <button id="lux-btn-theme-osint" style="background:${
          state.icebergMode ? "#0891b2" : "#1e293b"
        };color:#f8fafc;border:none;border-radius:999px;padding:5px 11px;font-size:12px;font-weight:600;cursor:pointer;">
          🧊 osint / iceberg
        </button>
        <button id="lux-btn-config-2b" style="background:#1e293b;color:#38bdf8;border:1px solid #334155;border-radius:999px;padding:5px 11px;font-size:12px;cursor:pointer;">
          ⚙ Config 2B
        </button>
      </div>

      <div id="lux-panel-config-2b" style="display:none;margin-top:8px;width:360px;background:#0b1220;border:1px solid #1e3a5f;border-radius:12px;padding:14px;color:#e2e8f0;box-shadow:0 12px 32px rgba(0,0,0,0.6);">
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;">
          <strong style="color:#22d3ee;font-size:13px;">Config → Seção 2B (Iceberg Multi-Camada)</strong>
          <button id="lux-close-2b" style="background:none;border:none;color:#94a3b8;cursor:pointer;font-size:15px;">✕</button>
        </div>

        <label style="display:block;font-size:11px;color:#94a3b8;margin-bottom:3px;">Endpoint da Ponte Local</label>
        <input id="lux-inp-bridge" type="text" value="${state.bridgeUrl}" style="width:100%;box-sizing:border-box;background:#070b14;border:1px solid #334155;color:#f8fafc;padding:6px 8px;border-radius:6px;font-size:12px;margin-bottom:8px;" />

        <label style="display:block;font-size:11px;color:#94a3b8;margin-bottom:3px;">Instância SearXNG Self-Hosted</label>
        <input id="lux-inp-searxng" type="text" value="${state.searxngUrl}" style="width:100%;box-sizing:border-box;background:#070b14;border:1px solid #334155;color:#f8fafc;padding:6px 8px;border-radius:6px;font-size:12px;margin-bottom:10px;" />

        <div style="display:grid;grid-template-columns:1fr 1fr;gap:6px;margin-bottom:10px;font-size:11px;">
          <div style="background:#070b14;padding:6px 8px;border-radius:6px;display:flex;justify-content:space-between;">
            <span>Ponte Local</span><span id="lux-st-bridge" style="padding:1px 6px;border-radius:4px;background:#334155;">?</span>
          </div>
          <div style="background:#070b14;padding:6px 8px;border-radius:6px;display:flex;justify-content:space-between;">
            <span>Tor (9050)</span><span id="lux-st-tor" style="padding:1px 6px;border-radius:4px;background:#334155;">?</span>
          </div>
          <div style="background:#070b14;padding:6px 8px;border-radius:6px;display:flex;justify-content:space-between;">
            <span>I2Pd (4444)</span><span id="lux-st-i2p" style="padding:1px 6px;border-radius:4px;background:#334155;">?</span>
          </div>
          <div style="background:#070b14;padding:6px 8px;border-radius:6px;display:flex;justify-content:space-between;">
            <span>SearXNG</span><span id="lux-st-searx" style="padding:1px 6px;border-radius:4px;background:#334155;">?</span>
          </div>
        </div>

        <div style="display:flex;flex-direction:column;gap:6px;">
          <button id="lux-btn-test-proxies" style="background:#0284c7;color:#fff;border:none;border-radius:6px;padding:7px;font-size:12px;font-weight:600;cursor:pointer;">
            🔍 Testar Proxies Tor / I2Pd / SearXNG
          </button>
          <button id="lux-btn-download-bridge" style="background:#1e293b;color:#e2e8f0;border:1px solid #334155;border-radius:6px;padding:7px;font-size:12px;cursor:pointer;">
            ⬇ Baixar lux_iceberg_bridge.py
          </button>
        </div>

        <div style="margin-top:10px;padding-top:8px;border-top:1px solid #1e293b;font-size:11px;color:#94a3b8;">
          <b>Comandos no Chat:</b><br>
          • <code>pesquisar iceberg &lt;consulta&gt;</code><br>
          • <code>inspecionar url &lt;endereço&gt;</code>
        </div>
      </div>
    `;

    document.body.appendChild(dock);

    const panel = document.getElementById("lux-panel-config-2b");
    document.getElementById("lux-btn-config-2b").onclick = () => {
      panel.style.display = panel.style.display === "none" ? "block" : "none";
    };
    document.getElementById("lux-close-2b").onclick = () => {
      panel.style.display = "none";
    };

    document.getElementById("lux-btn-theme-osint").onclick = (e) => {
      state.icebergMode = !state.icebergMode;
      localStorage.setItem(STORAGE_KEYS.ACTIVE_THEME, state.icebergMode ? "osint" : "default");
      e.target.style.background = state.icebergMode ? "#0891b2" : "#1e293b";
      logToLuxDB("THEME", `Aba temática osint / iceberg ${state.icebergMode ? "ATIVADA" : "DESATIVADA"}`);
    };

    document.getElementById("lux-inp-bridge").onchange = (e) => {
      state.bridgeUrl = e.target.value.trim();
      localStorage.setItem(STORAGE_KEYS.BRIDGE_URL, state.bridgeUrl);
    };
    document.getElementById("lux-inp-searxng").onchange = (e) => {
      state.searxngUrl = e.target.value.trim();
      localStorage.setItem(STORAGE_KEYS.SEARXNG_URL, state.searxngUrl);
    };

    document.getElementById("lux-btn-test-proxies").onclick = testProxies;
    document.getElementById("lux-btn-download-bridge").onclick = downloadBridgeScript;

    // Intercepta Enter no campo de chat principal para comandos diretos
    document.addEventListener(
      "keydown",
      async (ev) => {
        if (ev.key === "Enter" && !ev.shiftKey) {
          const active = document.activeElement;
          if (active && (active.tagName === "TEXTAREA" || active.tagName === "INPUT")) {
            const val = active.value || "";
            if (/^(?:pesquisar\s+iceberg|buscar\s+osint|inspecionar\s+url|acessar\s+onion|acessar\s+i2p)\s+/i.test(val.trim())) {
              ev.preventDefault();
              ev.stopPropagation();
              active.value = "";
              await handleDirectChatCommand(val);
            }
          }
        }
      },
      true
    );

    logToLuxDB("INIT", "Módulo LUX-Ω Iceberg (v5.4) carregado com sucesso.");
  }

  window.LUX_ICEBERG = {
    state,
    testProxies,
    executeIcebergSearch,
    executeOsintInspect,
    handleDirectChatCommand,
    processAutonomousTools,
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", injectUI);
  } else {
    injectUI();
  }
})();
