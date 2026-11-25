(() => {
  const cfg = window.SWIFTTV_SITE_CONFIG || {};
  const fb = cfg.fallbackRelease;
  const prefersReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const finePointer = window.matchMedia("(hover: hover) and (pointer: fine)");

  function motionAllowed() {
    return !prefersReducedMotion.matches && !document.documentElement.classList.contains("motion-paused");
  }

  // --- Toast helper ---
  const toastEl = document.getElementById("toast");
  let toastTimer = null;
  function showToast(msg) {
    if (!toastEl) return;
    toastEl.textContent = msg;
    toastEl.classList.add("visible");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastEl.classList.remove("visible"), 2400);
  }

  async function copyText(text, label) {
    try {
      await navigator.clipboard.writeText(text);
      showToast(`已复制${label}`);
    } catch {
      showToast("复制失败，请手动选中复制");
    }
  }

  // --- WCAG 2.2.2 Pause / Play toggle (label swaps, no aria-pressed) ---
  const motionBtn = document.getElementById("motionToggle");
  if (motionBtn) {
    motionBtn.addEventListener("click", () => {
      const paused = document.documentElement.classList.toggle("motion-paused");
      motionBtn.textContent = paused ? "恢复背景动效" : "暂停背景动效";
      if (paused) resetAllInteractiveTransforms();
    });
  }

  // --- Shrinking Frosted Header on Scroll (rAF throttled) ---
  const topbar = document.getElementById("topbar");
  if (topbar) {
    let scrollTicking = false;
    const syncHeader = () => {
      scrollTicking = false;
      topbar.classList.toggle("is-scrolled", window.scrollY > 24);
    };
    window.addEventListener(
      "scroll",
      () => {
        if (!scrollTicking) {
          scrollTicking = true;
          requestAnimationFrame(syncHeader);
        }
      },
      { passive: true }
    );
    syncHeader();
  }

  // --- Hero Staggered Reveal + One-Shot IntersectionObserver Scroll Reveal ---
  if (!prefersReducedMotion.matches) {
    const staggerEls = Array.from(document.querySelectorAll("[data-stagger]"));
    staggerEls.forEach((el) => {
      const idx = Number(el.dataset.stagger || 0);
      el.style.setProperty("--stagger-i", String(idx));
    });
    document.documentElement.classList.add("js-motion");
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        document.documentElement.classList.add("hero-ready");
      });
    });

    const revealTargets = Array.from(document.querySelectorAll("[data-reveal]"));
    if ("IntersectionObserver" in window && revealTargets.length > 0) {
      const revealObserver = new IntersectionObserver(
        (entries, obs) => {
          for (const entry of entries) {
            if (entry.isIntersecting) {
              entry.target.classList.add("is-visible");
              obs.unobserve(entry.target);
            }
          }
        },
        { threshold: 0.08, rootMargin: "0px 0px -36px 0px" }
      );
      revealTargets.forEach((el) => {
        const rect = el.getBoundingClientRect();
        if (rect.top < window.innerHeight * 0.96) {
          el.classList.add("is-visible");
        } else {
          revealObserver.observe(el);
        }
      });
    } else {
      revealTargets.forEach((el) => el.classList.add("is-visible"));
    }
  }

  // --- Interactive Hero TV Simulator (Up/Down Zapping + 4-Tier Elderly Font Scale) ---
  const SIM_CHANNELS = [
    {
      num: "01",
      name: "Apple 4K 官方演示流",
      spec: "1080p · HEVC · IPv6 · 310ms",
      prog: "正在播出：多码率 fMP4 自适应演示 · 即将：低延迟片段切换"
    },
    {
      num: "02",
      name: "Mux 演示直播",
      spec: "1080p · H.264 · IPv4 · 420ms",
      prog: "正在播出：Big Buck Bunny 演示节目 · 即将：高帧率测试段"
    },
    {
      num: "03",
      name: "Tears of Steel",
      spec: "4K · HEVC · IPv4 · 490ms",
      prog: "正在播出：Unified Streaming 4K 片源测试 · 即将：音轨切换"
    }
  ];

  let simIndex = 0;
  let simNaturalDownNext = true;
  const simHud = document.getElementById("simHud");
  const simRibbon = document.getElementById("simRibbon");
  const simNum = document.getElementById("simNum");
  const simName = document.getElementById("simName");
  const simSpec = document.getElementById("simSpec");
  const simProg = document.getElementById("simProg");
  const simCards = Array.from(document.querySelectorAll(".sim-card"));
  const simDirBtn = document.getElementById("simDirToggle");

  function renderSimChannel() {
    const ch = SIM_CHANNELS[simIndex];
    if (simNum) simNum.textContent = ch.num;
    if (simName) simName.textContent = ch.name;
    if (simSpec) simSpec.textContent = ch.spec;
    if (simProg) simProg.textContent = ch.prog;
    simCards.forEach((btn, idx) => btn.classList.toggle("active", idx === simIndex));
  }

  function stepSimChannel(delta) {
    simIndex = (simIndex + delta + SIM_CHANNELS.length) % SIM_CHANNELS.length;
    renderSimChannel();
  }

  document.getElementById("simKeyUp")?.addEventListener("click", () => {
    stepSimChannel(simNaturalDownNext ? -1 : 1);
  });
  document.getElementById("simKeyDown")?.addEventListener("click", () => {
    stepSimChannel(simNaturalDownNext ? 1 : -1);
  });
  simDirBtn?.addEventListener("click", () => {
    simNaturalDownNext = !simNaturalDownNext;
    simDirBtn.textContent = simNaturalDownNext ? "当前：↓ 下一个" : "当前：↑ 下一个";
  });
  simCards.forEach((card, idx) => {
    card.addEventListener("click", () => {
      simIndex = idx;
      renderSimChannel();
    });
  });

  const fontButtons = Array.from(document.querySelectorAll("[data-font-btn]"));
  fontButtons.forEach((btn) => {
    btn.addEventListener("click", () => {
      const tier = btn.dataset.fontBtn || "standard";
      if (simHud) simHud.dataset.font = tier;
      if (simRibbon) simRibbon.dataset.font = tier;
      fontButtons.forEach((b) => b.classList.toggle("active", b === btn));
    });
  });

  // --- Spotlight Glow + Damped 3D Tilt + Subtle Magnetic Buttons (Fine Pointer Only) ---
  const interactiveResetters = [];
  function resetAllInteractiveTransforms() {
    interactiveResetters.forEach((fn) => fn());
  }

  function attachSpotlight(el) {
    let rect = el.getBoundingClientRect();
    const ro = new ResizeObserver(() => {
      rect = el.getBoundingClientRect();
    });
    ro.observe(el);

    const isTilt = el.classList.contains("tilt-stage");
    let rafId = 0;
    let lastClientX = 0;
    let lastClientY = 0;

    const resetTilt = () => {
      if (rafId) {
        cancelAnimationFrame(rafId);
        rafId = 0;
      }
      el.classList.remove("is-tilting");
      el.style.removeProperty("--rx");
      el.style.removeProperty("--ry");
      el.style.removeProperty("--tilt-scale");
    };
    if (isTilt) interactiveResetters.push(resetTilt);

    el.addEventListener("pointerenter", () => {
      rect = el.getBoundingClientRect();
    });

    el.addEventListener("pointermove", (e) => {
      if (!finePointer.matches || !rect.width || !rect.height) return;
      lastClientX = e.clientX;
      lastClientY = e.clientY;
      if (rafId) return;
      rafId = requestAnimationFrame(() => {
        rafId = 0;
        const px = Math.max(0, Math.min(1, (lastClientX - rect.left) / rect.width));
        const py = Math.max(0, Math.min(1, (lastClientY - rect.top) / rect.height));
        el.style.setProperty("--mouse-x", `${(px * 100).toFixed(2)}%`);
        el.style.setProperty("--mouse-y", `${(py * 100).toFixed(2)}%`);
        if (isTilt && motionAllowed()) {
          el.classList.add("is-tilting");
          const ry = (px - 0.5) * 4.4;
          const rx = (0.5 - py) * 3.6;
          el.style.setProperty("--rx", `${rx.toFixed(2)}deg`);
          el.style.setProperty("--ry", `${ry.toFixed(2)}deg`);
          el.style.setProperty("--tilt-scale", "1.012");
        }
      });
    });

    el.addEventListener("pointerleave", resetTilt);
  }

  document.querySelectorAll(".spotlight-surface").forEach(attachSpotlight);

  document.querySelectorAll(".magnetic").forEach((btn) => {
    let rect = btn.getBoundingClientRect();
    let rafId = 0;
    let mx = 0;
    let my = 0;

    const resetMag = () => {
      if (rafId) {
        cancelAnimationFrame(rafId);
        rafId = 0;
      }
      btn.style.removeProperty("--mx");
      btn.style.removeProperty("--my");
    };
    interactiveResetters.push(resetMag);

    btn.addEventListener("pointerenter", () => {
      rect = btn.getBoundingClientRect();
    });
    btn.addEventListener("pointermove", (e) => {
      if (!finePointer.matches || !motionAllowed() || !rect.width || !rect.height) return;
      mx = ((e.clientX - rect.left) / rect.width - 0.5) * 8;
      my = ((e.clientY - rect.top) / rect.height - 0.5) * 6;
      if (rafId) return;
      rafId = requestAnimationFrame(() => {
        rafId = 0;
        btn.style.setProperty("--mx", `${mx.toFixed(2)}px`);
        btn.style.setProperty("--my", `${my.toFixed(2)}px`);
      });
    });
    btn.addEventListener("pointerleave", resetMag);
  });

  // --- Demo A: Multi-Host 2.5s Parallel Race Failover Visualizer ---
  const raceBtn = document.getElementById("runRaceBtn");
  const raceResultText = document.getElementById("raceResultText");
  const raceSavedText = document.getElementById("raceSavedText");
  let raceTimerIds = [];

  function clearRaceTimers() {
    raceTimerIds.forEach((id) => clearTimeout(id));
    raceTimerIds = [];
  }

  function runRaceDemo() {
    clearRaceTimers();
    const fills = [0, 1, 2].map((i) => document.getElementById(`laneFill${i}`));
    const statuses = [0, 1, 2].map((i) => document.getElementById(`laneStatus${i}`));

    fills.forEach((el) => {
      if (!el) return;
      el.className = "lane-fill";
      el.style.transform = "scaleX(0.08)";
    });
    statuses.forEach((el) => {
      if (!el) return;
      el.className = "lane-status";
      el.textContent = "并发探测中…";
    });
    if (raceResultText) raceResultText.textContent = "3 主机同时建连中（上限 2.5s）…";
    if (raceSavedText) raceSavedText.textContent = "测通中";

    raceTimerIds.push(setTimeout(() => {
      if (fills[0]) {
        fills[0].classList.add("fail");
        fills[0].style.transform = "scaleX(0.35)";
      }
      if (statuses[0]) {
        statuses[0].classList.add("fail");
        statuses[0].textContent = "HTTP 503 · 190ms 跳过";
      }
      if (fills[1]) fills[1].style.transform = "scaleX(0.55)";
      if (fills[2]) fills[2].style.transform = "scaleX(0.45)";
    }, 220));

    raceTimerIds.push(setTimeout(() => {
      if (fills[1]) {
        fills[1].classList.add("win");
        fills[1].style.transform = "scaleX(1)";
      }
      if (statuses[1]) {
        statuses[1].classList.add("win");
        statuses[1].textContent = "HTTP 200 HLS · 380ms 锁定播放";
      }
      if (fills[2]) {
        fills[2].classList.add("cancel");
        fills[2].style.transform = "scaleX(0.62)";
      }
      if (statuses[2]) {
        statuses[2].classList.add("cancel");
        statuses[2].textContent = "已自动取消慢请求";
      }
      if (raceResultText) raceResultText.textContent = "380ms 锁定最快线路 beta.edge";
      if (raceSavedText) raceSavedText.textContent = "较串行 8s 等待提速 95%";
    }, 520));
  }

  raceBtn?.addEventListener("click", runRaceDemo);
  runRaceDemo();

  // --- Demo B: Phone-to-TV Push Stepper ---
  const pushBtn = document.getElementById("runPushBtn");
  const handoffNodes = Array.from(document.querySelectorAll(".handoff-node"));
  let pushTimerIds = [];

  function runPushDemo() {
    pushTimerIds.forEach((id) => clearTimeout(id));
    pushTimerIds = [];
    handoffNodes.forEach((n, idx) => n.classList.toggle("active", idx === 0));

    pushTimerIds.push(setTimeout(() => {
      handoffNodes.forEach((n, idx) => n.classList.toggle("active", idx === 1));
    }, 650));

    pushTimerIds.push(setTimeout(() => {
      handoffNodes.forEach((n, idx) => n.classList.toggle("active", idx === 2));
    }, 1350));
  }

  pushBtn?.addEventListener("click", runPushDemo);

  // --- Showcase Accessible Tabs (aria-selected + hidden="until-found" + beforematch + smooth reveal) ---
  const tabButtons = Array.from(document.querySelectorAll(".tab-btn"));
  const tabPanels = Array.from(document.querySelectorAll(".showcase-panel"));

  function activateTab(selectedBtn, focusTab = false) {
    const targetId = selectedBtn.dataset.panel;
    tabButtons.forEach((b) => {
      const selected = b === selectedBtn;
      b.setAttribute("aria-selected", String(selected));
      b.tabIndex = selected ? 0 : -1;
    });
    tabPanels.forEach((panel) => {
      const show = panel.id === targetId;
      panel.classList.remove("is-animating");
      if (show) {
        panel.removeAttribute("hidden");
        panel.tabIndex = 0;
        panel.classList.add("is-active");
        if (motionAllowed()) {
          void panel.offsetWidth;
          panel.classList.add("is-animating");
        }
      } else {
        panel.setAttribute("hidden", "until-found");
        panel.removeAttribute("tabindex");
        panel.classList.remove("is-active");
      }
    });
    selectedBtn.scrollIntoView({ block: "nearest", inline: "nearest" });
    if (focusTab) selectedBtn.focus();
  }

  tabButtons.forEach((btn, idx) => {
    btn.tabIndex = btn.getAttribute("aria-selected") === "true" ? 0 : -1;
    btn.addEventListener("click", () => activateTab(btn));
    btn.addEventListener("keydown", (e) => {
      let nextIdx = null;
      if (e.key === "ArrowRight") nextIdx = (idx + 1) % tabButtons.length;
      else if (e.key === "ArrowLeft") nextIdx = (idx - 1 + tabButtons.length) % tabButtons.length;
      else if (e.key === "Home") nextIdx = 0;
      else if (e.key === "End") nextIdx = tabButtons.length - 1;
      if (nextIdx !== null) {
        e.preventDefault();
        activateTab(tabButtons[nextIdx], true);
      }
    });
  });

  tabPanels.forEach((panel) => {
    panel.addEventListener("beforematch", () => {
      const owner = tabButtons.find((b) => b.dataset.panel === panel.id);
      if (owner) activateTab(owner);
    });
  });

  // --- Render Official Test Streams & Optional External Portal ---
  const streamsGrid = document.getElementById("streamsGrid");
  const streams = Array.isArray(cfg.officialTestStreams) ? cfg.officialTestStreams : [];
  if (streamsGrid) {
    streams.forEach((item, idx) => {
      const card = document.createElement("article");
      card.className = "stream-card spotlight-surface";
      card.setAttribute("data-reveal", "");
      card.style.setProperty("--reveal-delay", `${idx * 70}ms`);

      const spot = document.createElement("div");
      spot.className = "spotlight-layer";
      spot.setAttribute("aria-hidden", "true");

      const badge = document.createElement("span");
      badge.className = "card-index";
      badge.textContent = item.badge || "公开测试";

      const title = document.createElement("h3");
      title.textContent = item.name || "";

      const desc = document.createElement("p");
      desc.textContent = item.desc || "";

      const urlBox = document.createElement("code");
      urlBox.className = "stream-url";
      urlBox.textContent = item.url || "";

      const copyBtn = document.createElement("button");
      copyBtn.type = "button";
      copyBtn.className = "btn-crystal btn-sm";
      copyBtn.textContent = "复制地址（用于扫码 / 助手推送）";
      copyBtn.addEventListener("click", () => copyText(item.url || "", "测试流地址"));

      card.append(spot, badge, title, desc, urlBox, copyBtn);
      streamsGrid.appendChild(card);
      attachSpotlight(card);

      if (prefersReducedMotion.matches || !("IntersectionObserver" in window)) {
        card.classList.add("is-visible");
      } else {
        const obs = new IntersectionObserver(
          (entries, o) => {
            if (entries[0]?.isIntersecting) {
              card.classList.add("is-visible");
              o.disconnect();
            }
          },
          { threshold: 0.08 }
        );
        obs.observe(card);
      }
    });
  }

  const portalMount = document.getElementById("externalPortalMount");
  const portal = cfg.externalPortal;
  if (portalMount && portal && portal.enabled && typeof portal.url === "string") {
    try {
      const parsed = new URL(portal.url);
      if (parsed.protocol === "https:" || parsed.protocol === "http:") {
        const box = document.createElement("aside");
        box.className = "external-portal";

        const info = document.createElement("div");
        const eyebrow = document.createElement("span");
        eyebrow.className = "card-index";
        eyebrow.textContent = portal.badge || "外部导航";

        const heading = document.createElement("h3");
        heading.textContent = portal.title || "外部整理站点";

        const desc = document.createElement("p");
        desc.textContent = portal.desc || "";

        info.append(eyebrow, heading, desc);

        const cta = document.createElement("a");
        cta.className = "btn-porcelain btn-lg sheen-btn";
        cta.href = parsed.href;
        cta.target = "_blank";
        cta.rel = "noopener noreferrer";
        cta.textContent = portal.buttonLabel || "前往外部站点 ↗";

        box.append(info, cta);
        portalMount.appendChild(box);
      }
    } catch {
      // Ignore malformed external portal URL.
    }
  }

  // --- Dynamic GitHub Releases Latest Resolution with Fallback ---
  function formatBytes(bytes) {
    if (!Number.isFinite(bytes) || bytes <= 0) return "";
    if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    return `${Math.round(bytes / 1024)} KB`;
  }

  function applyReleaseData(data) {
    const tag = data.tag || fb.tag;
    const setHref = (id, url) => {
      const el = document.getElementById(id);
      if (el && url) el.href = url;
    };
    const setText = (id, val) => {
      const el = document.getElementById(id);
      if (el && val) el.textContent = val;
    };

    setText("navVersionTag", tag);
    setText("dlTvVersion", tag);
    setText("heroTvMeta", `${tag} · ${data.tvApk.sizeLabel}`);
    setText("dlTvSize", data.tvApk.sizeLabel);
    setHref("heroTvDownload", data.tvApk.url);
    setHref("dlTvBtn", data.tvApk.url);

    setText("dlInstVersion", data.instVerLabel || fb.installerApk.name.replace(/^SwiftTV-Installer-|\.apk$/g, ""));
    setText("heroInstallerMeta", `${data.instVerLabel || "v1.1.1"} · 无线装 & 推源`);
    setText("dlInstSize", data.installerApk.sizeLabel);
    setHref("heroInstallerDownload", data.installerApk.url);
    setHref("dlInstBtn", data.installerApk.url);

    setHref("heroReleaseLink", data.releaseUrl);
    setHref("dlChecksumsLink", data.checksumsUrl);
    setHref("dlAllReleasesLink", fb.allReleasesUrl);

    if (data.tvApk.sha256) setText("dlTvSha", data.tvApk.sha256);
    if (data.installerApk.sha256) setText("dlInstSha", data.installerApk.sha256);
  }

  if (fb) {
    applyReleaseData({
      tag: fb.tag,
      releaseUrl: fb.releaseUrl,
      checksumsUrl: fb.checksumsUrl,
      tvApk: fb.tvApk,
      installerApk: fb.installerApk,
      instVerLabel: "v1.1.1"
    });
  }

  document.getElementById("copyTvSha")?.addEventListener("click", () => {
    copyText(document.getElementById("dlTvSha")?.textContent || "", "电视版 SHA-256");
  });
  document.getElementById("copyInstSha")?.addEventListener("click", () => {
    copyText(document.getElementById("dlInstSha")?.textContent || "", "安装助手 SHA-256");
  });

  async function syncLatestRelease() {
    const statusNote = document.getElementById("releaseStatusNote");
    if (!cfg.repo) return;
    try {
      const resp = await fetch(`https://api.github.com/repos/${cfg.repo}/releases/latest`, {
        headers: { Accept: "application/vnd.github+json" }
      });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const json = await resp.json();
      const assets = Array.isArray(json.assets) ? json.assets : [];
      const tvAsset = assets.find((a) => /^SwiftTV-\d.*\.apk$/i.test(a.name));
      const instAsset = assets.find((a) => /^SwiftTV-Installer-.*\.apk$/i.test(a.name));
      const sumAsset = assets.find((a) => a.name === "SHA256SUMS.txt");

      if (tvAsset && instAsset) {
        const instTagMatch = instAsset.name.match(/SwiftTV-Installer-(.+)\.apk$/i);
        applyReleaseData({
          tag: json.tag_name || fb.tag,
          releaseUrl: json.html_url || fb.releaseUrl,
          checksumsUrl: sumAsset ? sumAsset.browser_download_url : fb.checksumsUrl,
          tvApk: {
            name: tvAsset.name,
            url: tvAsset.browser_download_url,
            sizeLabel: formatBytes(tvAsset.size) || fb.tvApk.sizeLabel,
            sha256: json.tag_name === fb.tag ? fb.tvApk.sha256 : "见 SHA256SUMS.txt"
          },
          installerApk: {
            name: instAsset.name,
            url: instAsset.browser_download_url,
            sizeLabel: formatBytes(instAsset.size) || fb.installerApk.sizeLabel,
            sha256: json.tag_name === fb.tag ? fb.installerApk.sha256 : "见 SHA256SUMS.txt"
          },
          instVerLabel: instTagMatch ? `v${instTagMatch[1]}` : "v1.1.1"
        });
        if (statusNote) {
          statusNote.textContent = `已实时同步 GitHub 最新正式版 ${json.tag_name}（直链下载）。`;
        }
      }
    } catch {
      if (statusNote) {
        statusNote.textContent = `当前使用预置版本清单 ${fb?.tag || "v0.4.1"}（直连 GitHub Releases）。`;
      }
    }
  }
  syncLatestRelease();
})();
