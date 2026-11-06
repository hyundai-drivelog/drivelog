/* Card images are drawn on canvas so the preview and the exported file are the same pixels. */
/*
 * The same card style as the car and the QR phone card (web/card): one big number with its label beneath, the route
 * as a lit white line on open space, a ticket's tear line before the figures, place · time · date in the footer, and
 * the brand small in a corner. What is ours is the stamp: a real drive that ended at a stamp place carries a postage
 * stamp in the top right.
 */
(function () {
  const W = 1080, H = 1350, PAD = 72;
  const FONT = '-apple-system, "Apple SD Gothic Neo", "Noto Sans KR", "Malgun Gothic", sans-serif';
  // Pleos tokens: the dark card and its light paper for the journal template.
  const DARK = { paper: '#131417', ink: '#ffffff', muted: '#b4b6ba', faint: 'rgba(255,255,255,.4)', dot: '255,255,255' };
  const LIGHT = { paper: '#ffffff', ink: '#131417', muted: '#686a72', faint: 'rgba(19,20,23,.4)', dot: '19,20,23' };

  // The car's stamp places (app StampPlaces.kt), so the phone presses the same stamp the car gave. Keep in step.
  const STAMPS = [
    ['광안리 해수욕장', 35.1532, 129.1186, -6], ['해운대 해수욕장', 35.1587, 129.1604, 4],
    ['송정 해수욕장', 35.1786, 129.1996, -3], ['해동용궁사', 35.1884, 129.2233, 7],
    ['오륙도 스카이워크', 35.1006, 129.1245, -8], ['흰여울문화마을', 35.0786, 129.0447, 5],
    ['태종대', 35.0532, 129.0870, -5], ['다대포 해수욕장', 35.0466, 128.9662, 3],
  ];

  function font(ctx, weight, size) { ctx.font = `${weight} ${size}px ${FONT}`; }

  function text(ctx, value, x, y, weight, size, color, align) {
    font(ctx, weight, size);
    ctx.fillStyle = color;
    ctx.textAlign = align || 'left';
    ctx.textBaseline = 'alphabetic';
    ctx.fillText(value, x, y);
  }

  function ellipsize(ctx, value, maxWidth) {
    if (ctx.measureText(value).width <= maxWidth) return value;
    let cut = value;
    while (cut.length > 1 && ctx.measureText(cut + '…').width > maxWidth) cut = cut.slice(0, -1);
    return cut + '…';
  }

  /** Cover-fit with user pan/zoom. crop = {zoom >= 1, x, y in -1..1}. */
  function drawPhoto(ctx, photo, rect, crop, mono, radius) {
    ctx.save();
    ctx.beginPath();
    if (radius) ctx.roundRect(rect.x, rect.y, rect.w, rect.h, radius); else ctx.rect(rect.x, rect.y, rect.w, rect.h);
    ctx.clip();
    if (photo) {
      const zoom = crop ? crop.zoom : 1;
      const scale = Math.max(rect.w / photo.width, rect.h / photo.height) * zoom;
      const dw = photo.width * scale, dh = photo.height * scale;
      const panX = (dw - rect.w) / 2 * (crop ? crop.x : 0);
      const panY = (dh - rect.h) / 2 * (crop ? crop.y : 0);
      if (mono) ctx.filter = 'grayscale(1) contrast(1.05)';
      ctx.drawImage(photo, rect.x + (rect.w - dw) / 2 + panX, rect.y + (rect.h - dh) / 2 + panY, dw, dh);
      ctx.filter = 'none';
    } else {
      ctx.fillStyle = '#d9d9db';
      ctx.fillRect(rect.x, rect.y, rect.w, rect.h);
    }
    ctx.restore();
  }

  /** Hide both ends by default: they reveal where a drive started and ended, often a home address. */
  function visibleRoute(route, trimEnds) {
    const pts = (route || []).filter(p => Array.isArray(p) && Number.isFinite(p[0]) && Number.isFinite(p[1]));
    if (!trimEnds || pts.length < 12) return pts;
    const cut = Math.max(1, Math.round(pts.length * 0.1));
    return pts.slice(cut, pts.length - cut);
  }

  function projectRoute(pts, rect) {
    if (pts.length < 2) return [];
    const lat0 = pts.reduce((sum, p) => sum + p[0], 0) / pts.length;
    const k = Math.cos(lat0 * Math.PI / 180);
    const xs = pts.map(p => p[1] * k), ys = pts.map(p => -p[0]);
    const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys);
    const dx = Math.max(maxX - minX, 1e-7), dy = Math.max(maxY - minY, 1e-7);
    const scale = Math.min(rect.w / dx, rect.h / dy);
    const ox = rect.x + (rect.w - dx * scale) / 2, oy = rect.y + (rect.h - dy * scale) / 2;
    return pts.map((p, i) => [ox + (xs[i] - minX) * scale, oy + (ys[i] - minY) * scale, p[2]]);
  }

  /** A recording gap (no fix for over a minute) is drawn dotted, never smoothed over. */
  function routePaths(shape) {
    const solid = new Path2D(), dashed = new Path2D();
    shape.forEach((p, i) => {
      if (i === 0) { solid.moveTo(p[0], p[1]); return; }
      const prev = shape[i - 1];
      if (p[2] && prev[2] && p[2] - prev[2] > 60000) { dashed.moveTo(prev[0], prev[1]); dashed.lineTo(p[0], p[1]); solid.moveTo(p[0], p[1]); }
      else solid.lineTo(p[0], p[1]);
    });
    return { solid, dashed };
  }

  /** The plain line, as the record screen's small preview uses it. */
  function drawRoute(ctx, pts, rect, color, width, shadow) {
    const shape = projectRoute(pts, rect);
    if (!shape.length) return;
    const { solid, dashed } = routePaths(shape);
    ctx.save();
    ctx.lineJoin = 'round'; ctx.lineCap = 'round'; ctx.strokeStyle = color; ctx.lineWidth = width;
    if (shadow) { ctx.shadowColor = 'rgba(0,0,0,.45)'; ctx.shadowBlur = 12; }
    ctx.stroke(solid);
    ctx.setLineDash([width * 0.4, width * 2.4]);
    ctx.stroke(dashed);
    ctx.restore();
  }

  /** The card's line: a wide soft pass, then the line itself with a glow, a ring at the start and a dot at the end. */
  function drawCardRoute(ctx, pts, rect, t, overPhoto) {
    const shape = projectRoute(pts, rect);
    if (!shape.length) return;
    const { solid, dashed } = routePaths(shape);
    ctx.save();
    ctx.lineJoin = 'round'; ctx.lineCap = 'round';
    ctx.strokeStyle = t.faint; ctx.lineWidth = 6; ctx.setLineDash([4, 18]); ctx.stroke(dashed); ctx.setLineDash([]);
    // As the car and the phone card draw it: one clean line, no glow. Over a photo a soft shadow keeps it readable.
    ctx.strokeStyle = t.ink; ctx.lineWidth = 10;
    if (overPhoto) { ctx.shadowColor = 'rgba(0,0,0,.5)'; ctx.shadowBlur = 16; }
    ctx.stroke(solid);
    ctx.restore();
    const start = shape[0], end = shape[shape.length - 1];
    ctx.fillStyle = t.paper; ctx.beginPath(); ctx.arc(start[0], start[1], 15, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = t.ink; ctx.beginPath(); ctx.arc(start[0], start[1], 9, 0, Math.PI * 2); ctx.fill();
    // The car's arrow where the drive ended, pointing the way it went; the heading from a little way back.
    let from = start;
    for (let i = shape.length - 2; i >= 0; i--) if (Math.hypot(shape[i][0] - end[0], shape[i][1] - end[1]) >= 24) { from = shape[i]; break; }
    ctx.save();
    ctx.translate(end[0], end[1]);
    ctx.shadowColor = 'rgba(0,0,0,.45)'; ctx.shadowBlur = 16;
    ctx.fillStyle = t.ink; ctx.beginPath(); ctx.arc(0, 0, 32, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
    ctx.rotate(Math.atan2(end[1] - from[1], end[0] - from[0]) + Math.PI / 2);
    ctx.fillStyle = t.paper; ctx.beginPath();
    ctx.moveTo(0, -17); ctx.lineTo(13, 14); ctx.lineTo(0, 7); ctx.lineTo(-13, 14); ctx.closePath(); ctx.fill();
    ctx.restore();
  }

  /** Dots that thin out towards the edges: no box around the drive, and plainly not a map. */
  function drawDots(ctx, box, t) {
    const cx = box.x + box.w / 2, cy = box.y + box.h / 2, reach = Math.hypot(box.w, box.h) / 2;
    for (let y = box.y + 20; y < box.y + box.h; y += 40) for (let x = box.x + 20; x < box.x + box.w; x += 40) {
      const a = 0.14 * Math.max(0, 1 - Math.hypot(x - cx, y - cy) / reach);
      if (a < 0.01) continue;
      ctx.fillStyle = `rgba(${t.dot},${a.toFixed(3)})`;
      ctx.beginPath(); ctx.arc(x, y, 2, 0, Math.PI * 2); ctx.fill();
    }
  }

  function drawBackground(ctx, t) {
    ctx.fillStyle = t.paper; ctx.fillRect(0, 0, W, H);
    const glow = ctx.createRadialGradient(W / 2, 580, 0, W / 2, 580, 560);
    glow.addColorStop(0, `rgba(${t.dot},.05)`); glow.addColorStop(1, `rgba(${t.dot},0)`);
    ctx.fillStyle = glow; ctx.fillRect(0, 0, W, H);
  }

  /** The cut between the route and the figures, as on a ticket stub. Notches only where the card has a plain edge. */
  function drawTearLine(ctx, y, t, notches) {
    ctx.save();
    ctx.setLineDash([2, 14]); ctx.lineCap = 'round'; ctx.lineWidth = 4; ctx.strokeStyle = `rgba(${t.dot},.28)`;
    ctx.beginPath(); ctx.moveTo(48, y); ctx.lineTo(W - 48, y); ctx.stroke();
    ctx.restore();
    if (!notches) return;
    ctx.save(); ctx.globalCompositeOperation = 'destination-out';
    for (const x of [0, W]) { ctx.beginPath(); ctx.arc(x, y, 24, 0, Math.PI * 2); ctx.fill(); }
    ctx.restore();
  }

  function wave(ctx, x0, x1, y, amplitude, period, phase) {
    ctx.beginPath();
    for (let x = x0; x <= x1; x += 3) {
      const at = y + Math.sin((x + phase) / period) * amplitude;
      if (x === x0) ctx.moveTo(x, at); else ctx.lineTo(x, at);
    }
    ctx.stroke();
  }

  /** A postage stamp: perforated paper, the sea every Busan stamp place sits by, the place name, the place's tilt. */
  function drawStamp(ctx, cx, cy, name, tilt, t) {
    const w = 208, h = 244, r = 9;
    // Drawn apart and then set down, so the perforations are real holes over a photo too.
    const sheet = document.createElement('canvas');
    sheet.width = w + 2 * r; sheet.height = h + 2 * r;
    const s = sheet.getContext('2d');
    s.translate(sheet.width / 2, sheet.height / 2);
    s.fillStyle = t.ink; s.fillRect(-w / 2, -h / 2, w, h);
    s.globalCompositeOperation = 'destination-out';
    for (let i = 0; i <= 8; i++) for (const y of [-h / 2, h / 2]) { s.beginPath(); s.arc(-w / 2 + i * w / 8, y, r, 0, Math.PI * 2); s.fill(); }
    for (let i = 0; i <= 9; i++) for (const x of [-w / 2, w / 2]) { s.beginPath(); s.arc(x, -h / 2 + i * h / 9, r, 0, Math.PI * 2); s.fill(); }
    s.globalCompositeOperation = 'source-over';
    s.fillStyle = t.paper; s.fillRect(-w / 2 + 18, -h / 2 + 18, w - 36, h - 36);
    s.textAlign = 'center';
    font(s, 700, 20); s.fillStyle = t.muted; s.fillText('부산 스탬프', 0, -h / 2 + 62, w - 44);
    s.strokeStyle = t.ink; s.lineWidth = 4; s.lineCap = 'round';
    for (let row = 0; row < 3; row++) wave(s, -62, 62, -14 + row * 18, 5, 11, row * 14);
    font(s, 800, 36); s.fillStyle = t.ink; s.fillText(shortPlace(name), 0, h / 2 - 42, w - 52);
    ctx.save();
    ctx.translate(cx, cy); ctx.rotate(tilt * Math.PI / 180);
    ctx.drawImage(sheet, -sheet.width / 2, -sheet.height / 2);
    ctx.restore();
    // The postmark's cancel lines, running off the stamp's left edge.
    ctx.save(); ctx.strokeStyle = t.faint; ctx.lineWidth = 3; ctx.lineCap = 'round';
    for (let row = 0; row < 3; row++) wave(ctx, cx - 200, cx - 40, cy + 26 + row * 20, 6, 13, 0);
    ctx.restore();
    ctx.textAlign = 'left';
  }

  function haversineKm(a, b) {
    const r = Math.PI / 180, dLat = (b[0] - a[0]) * r, dLon = (b[1] - a[1]) * r;
    const h = Math.sin(dLat / 2) ** 2 + Math.cos(a[0] * r) * Math.cos(b[0] * r) * Math.sin(dLon / 2) ** 2;
    return 6371.0088 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
  }

  /** As the car decides it: a real drive of 0.1 km or more that ended within 1 km of the place, the nearest winning. */
  function stampFor(record) {
    const route = record.route || [];
    const end = route[route.length - 1];
    if (record.simulated || !end || !(record.distanceKm >= 0.1)) return null;
    let best = null, bestKm = 1.0;
    for (const s of STAMPS) {
      const km = haversineKm([end[0], end[1]], [s[1], s[2]]);
      if (km <= bestKm) { best = s; bestKm = km; }
    }
    return best && { name: best[0], tilt: best[3] };
  }

  function distanceLabel(record) {
    return record.distanceSource === 'VEHICLE' ? '차량 주행거리' : '추정 거리';
  }

  /** As in the car: under a minute is said so, never "0분". */
  function minutes(record) {
    const m = Math.floor((record.elapsedSeconds || 0) / 60);
    if (m < 1) return '1분 미만';
    return m < 60 ? `${m}분` : `${Math.floor(m / 60)}시간 ${m % 60}분`;
  }

  function date(ms) {
    const d = new Date(ms);
    return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')}`;
  }

  function shortPlace(name) { return name.replace(/\s*(해수욕장|해변)$/, ''); }

  /** Truthful labels stay on the image even when other fields are hidden. */
  function caveats(record) {
    const out = [];
    if (record.status === 'PARTIAL') out.push('불완전한 기록');
    if (record.simulated) out.push('시뮬레이션');
    return out;
  }

  /** The figure with its unit at 0.4 of its size, then what it is beneath it. Caveats stay even with it hidden. */
  function drawFigure(ctx, record, o, t, unitColor) {
    if (o.showDistance) {
      const value = record.distanceKm == null ? '—' : record.distanceKm.toFixed(1);
      text(ctx, value, PAD - 8, 1168, 800, 210, t.ink);
      if (record.distanceKm != null) {
        font(ctx, 800, 210);
        const w = ctx.measureText(value).width;
        text(ctx, 'km', PAD - 8 + w + 18, 1168, 800, 84, unitColor || t.muted);
      }
    }
    const label = (o.showDistance ? [distanceLabel(record)] : []).concat(caveats(record)).join(' · ');
    if (label) text(ctx, label, PAD, 1218, 500, 28, unitColor || t.muted);
  }

  /** place · time · date with fainter dots, and the brand in the corner. */
  function drawFooter(ctx, record, o, t) {
    const parts = [o.showPlace ? shortPlace(record.placeName) : null, o.showTime ? minutes(record) : null, o.showDate ? date(record.startedAtMillis) : null].filter(Boolean);
    font(ctx, 500, 30);
    let x = PAD;
    const right = W - PAD - 110;
    parts.forEach((part, i) => {
      if (i > 0) { ctx.fillStyle = t.faint; ctx.fillText(' · ', x, 1288); x += ctx.measureText(' · ').width; }
      const room = Math.max(40, right - x);
      ctx.fillStyle = t.ink; ctx.fillText(part, x, 1288, room); x += Math.min(ctx.measureText(part).width, room);
    });
    text(ctx, '여정', W - PAD, 1288, 800, 36, t.ink, 'right');
  }

  /** Eyebrow and title, kept clear of the stamp. The stamp names the place, so it follows the place toggle. */
  function drawHeading(ctx, record, o, t, eyebrow) {
    const stamp = o.showPlace ? stampFor(record) : null;
    text(ctx, eyebrow, PAD, 116, 700, 28, t.muted);
    font(ctx, 800, 66);
    text(ctx, ellipsize(ctx, o.title, W - PAD * 2 - (stamp ? 250 : 0)), PAD, 196, 800, 66, t.ink);
    return stamp;
  }

  /** The photo fills the card; the same layout as every card sits over it. */
  function drawPhotoTemplate(ctx, record, photo, o) {
    const t = Object.assign({}, DARK, { muted: 'rgba(255,255,255,.85)', faint: 'rgba(255,255,255,.55)' });
    drawPhoto(ctx, photo, { x: 0, y: 0, w: W, h: H }, o.crop, o.tone !== 'color');
    const top = ctx.createLinearGradient(0, 0, 0, 380);
    top.addColorStop(0, 'rgba(0,0,0,.55)'); top.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = top; ctx.fillRect(0, 0, W, 380);
    const bottom = ctx.createLinearGradient(0, 860, 0, H);
    bottom.addColorStop(0, 'rgba(0,0,0,0)'); bottom.addColorStop(0.35, 'rgba(0,0,0,.55)'); bottom.addColorStop(1, 'rgba(0,0,0,.82)');
    ctx.fillStyle = bottom; ctx.fillRect(0, 860, W, H - 860);
    if (o.tone === 'mono-dark') { ctx.fillStyle = 'rgba(0,0,0,.3)'; ctx.fillRect(0, 0, W, H); }
    const stamp = drawHeading(ctx, record, o, t, '나의 드라이브');
    if (o.showRoute) drawCardRoute(ctx, visibleRoute(record.route, o.trimEnds), { x: PAD + 60, y: stamp ? 340 : 290, w: W - PAD * 2 - 120, h: stamp ? 540 : 590 }, t, true);
    if (stamp) drawStamp(ctx, W - PAD - 104, 160, stamp.name, stamp.tilt, DARK);
    drawTearLine(ctx, 960, t, false);
    drawFigure(ctx, record, o, t, 'rgba(255,255,255,.85)');
    drawFooter(ctx, record, o, t);
  }

  /** The card on its own paper, light or dark, with the photo set into it above the route. */
  function drawJournalTemplate(ctx, record, photo, o) {
    const t = o.tone === 'mono-dark' ? DARK : LIGHT;
    drawBackground(ctx, t);
    const stamp = drawHeading(ctx, record, o, t, '나의 드라이브');
    const photoBox = { x: PAD, y: stamp ? 300 : 250, w: W - PAD * 2, h: stamp ? 400 : 450 };
    drawPhoto(ctx, photo, photoBox, o.crop, o.tone !== 'color', 24);
    if (o.showRoute) {
      const box = { x: PAD, y: 720, w: W - PAD * 2, h: 220 };
      drawDots(ctx, box, t);
      drawCardRoute(ctx, visibleRoute(record.route, o.trimEnds), { x: box.x + 60, y: box.y + 34, w: box.w - 120, h: box.h - 68 }, t, false);
    }
    if (stamp) drawStamp(ctx, W - PAD - 104, 160, stamp.name, stamp.tilt, t);
    drawTearLine(ctx, 960, t, true);
    drawFigure(ctx, record, o, t);
    drawFooter(ctx, record, o, t);
  }

  /** Goal completion: stacked place photos; a missing photo is an explicit placeholder. */
  function drawGoalCard(ctx, goal, recordsByPlace, photos) {
    const t = DARK;
    drawBackground(ctx, t);
    text(ctx, '목표 완성', PAD, 116, 700, 28, t.muted);
    font(ctx, 800, 66);
    text(ctx, ellipsize(ctx, goal.name, W - PAD * 2), PAD, 196, 800, 66, t.ink);
    text(ctx, `${Object.keys(recordsByPlace).length} / ${goal.places.length}곳 방문 완료`, PAD, 248, 500, 30, t.muted);
    const top = 290, gap = 16, rowH = Math.floor((H - top - 140 - gap * 2) / 3);
    goal.places.forEach((place, i) => {
      const y = top + i * (rowH + gap), rect = { x: PAD, y, w: W - PAD * 2, h: rowH };
      const photo = photos[place.id];
      drawPhoto(ctx, photo, rect, null, false, 24);
      if (!photo) text(ctx, '사진을 추가해 보세요', W / 2, y + rowH / 2 + 12, 500, 30, '#686a72', 'center');
      ctx.save(); ctx.beginPath(); ctx.roundRect(rect.x, rect.y, rect.w, rect.h, 24); ctx.clip();
      const shade = ctx.createLinearGradient(0, y + rowH - 110, 0, y + rowH);
      shade.addColorStop(0, 'rgba(0,0,0,0)'); shade.addColorStop(1, 'rgba(0,0,0,.65)');
      ctx.fillStyle = shade; ctx.fillRect(rect.x, y + rowH - 110, rect.w, 110);
      ctx.restore();
      text(ctx, shortPlace(place.name), PAD + 28, y + rowH - 28, 800, 40, '#fff');
      const record = recordsByPlace[place.id];
      if (record) text(ctx, date(record.startedAtMillis), W - PAD - 28, y + rowH - 30, 500, 26, '#fff', 'right');
    });
    const all = Object.values(recordsByPlace);
    const sum = all.reduce((total, r) => total + (r.distanceKm || 0), 0);
    const notes = [sum > 0 ? `총 ${sum.toFixed(1)} km · 추정 거리 포함` : null, all.some(r => r.simulated) ? '시뮬레이션 포함' : null].filter(Boolean);
    font(ctx, 500, 28);
    let x = PAD;
    notes.forEach((note, i) => {
      if (i > 0) { ctx.fillStyle = t.faint; ctx.fillText(' · ', x, 1288); x += ctx.measureText(' · ').width; }
      ctx.fillStyle = t.muted; ctx.fillText(note, x, 1288); x += ctx.measureText(note).width;
    });
    text(ctx, '여정', W - PAD, 1288, 800, 36, t.ink, 'right');
  }

  window.DriveCard = {
    W, H, visibleRoute, drawRoute, date, minutes, shortPlace, distanceLabel,
    renderRecord(canvas, record, photo, options) {
      canvas.width = W; canvas.height = H;
      const ctx = canvas.getContext('2d');
      ctx.clearRect(0, 0, W, H);
      if (options.template === 'JOURNAL') drawJournalTemplate(ctx, record, photo, options);
      else drawPhotoTemplate(ctx, record, photo, options);
    },
    renderGoal(canvas, goal, recordsByPlace, photos) {
      canvas.width = W; canvas.height = H;
      drawGoalCard(canvas.getContext('2d'), goal, recordsByPlace, photos);
    },
  };
})();
