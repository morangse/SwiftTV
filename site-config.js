// SwiftTV 官网静态配置文件（可直接编辑后部署到 GitHub Pages）
window.SWIFTTV_SITE_CONFIG = {
  repo: "morangse/SwiftTV",

  // GitHub API 限流或离线时使用的保底发行版信息（会在加载后自动尝试从 GitHub Releases Latest 实时更新）
  fallbackRelease: {
    tag: "v0.4.4",
    publishedAt: "2026-11-27",
    releaseUrl: "https://github.com/morangse/SwiftTV/releases/tag/v0.4.4",
    allReleasesUrl: "https://github.com/morangse/SwiftTV/releases",
    tvApk: {
      name: "SwiftTV-0.4.4.apk",
      url: "https://github.com/morangse/SwiftTV/releases/download/v0.4.4/SwiftTV-0.4.4.apk",
      sizeLabel: "3.5 MB",
      sha256: "29534b6b730682a407281dcf6ec5fb7d1c0c90cec5822b8b23713b01b2d71c29"
    },
    liteApk: {
      name: "SwiftTV-Lite-0.4.4.apk",
      url: "https://github.com/morangse/SwiftTV/releases/download/v0.4.4/SwiftTV-Lite-0.4.4.apk",
      sizeLabel: "3.3 MB",
      sha256: "53879b61402068c2fb48f749c6afc7a2fe332b4eb41f211162979e201adc7b"
    },
    installerApk: {
      name: "SwiftTV-Installer-1.1.4.apk",
      url: "https://github.com/morangse/SwiftTV/releases/download/v0.4.4/SwiftTV-Installer-1.1.4.apk",
      sizeLabel: "22.1 MB",
      sha256: "09d381d4119b110507615cca0f84cfa61ec97ef8e020faa38fbb8ddd27d71a6e"
    },
    checksumsUrl: "https://github.com/morangse/SwiftTV/releases/download/v0.4.4/SHA256SUMS.txt"
  },

  // 合法公开测试源与遵循 DMCA 的开源免费列表（仅作播放器功能与格式调试用途）
  officialTestStreams: [
    {
      badge: "官方 HLS 测试",
      name: "Apple 多码率 fMP4 测试流",
      desc: "验证 HEVC / fMP4 自适应切换与首帧速度（单频道 .m3u8）",
      url: "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"
    },
    {
      badge: "官方 HLS 测试",
      name: "Mux Big Buck Bunny 测试流",
      desc: "适合测试流畅度、字幕与低延迟缓冲策略（单频道 .m3u8）",
      url: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    },
    {
      badge: "开源免费列表",
      name: "iptv-org 中文公开免费频道",
      desc: "社区遵循 DMCA 整理的公开免费网络广播流（46.8 KB M3U）",
      url: "https://iptv-org.github.io/iptv/languages/zho.m3u"
    }
  ],

  // 外部源整理站点跳转槽位（默认隐藏；如需接入您自己的独立整理站点，可将 enabled 设为 true 并填写 url）
  externalPortal: {
    enabled: false,
    badge: "外部资源导航",
    title: "社区公开源整理站点（外部跳转）",
    desc: "将跳转至独立维护的外部网页。SwiftTV 本身不内置、不抓取也不托管任何直播源，第三方链接的合法性与可用性由访问者自行核验并承担。",
    buttonLabel: "前往外部整理页 ↗",
    url: ""
  }
};
