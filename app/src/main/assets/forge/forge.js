// DK Website Forge - 3D "Forge Reactor" scene (Three.js r160, bundled locally, no network).
// API: Forge.init(themeJson, opts) / Forge.setState(state) / Forge.reveal() / Forge.fail(msg) / Forge.dispose()
// Android bridge (window.ForgeBridge): onReady(), onRevealFinished(), onWebGLFailed()
import * as THREE from 'three';
import { EffectComposer } from './jsm/postprocessing/EffectComposer.js';
import { RenderPass } from './jsm/postprocessing/RenderPass.js';
import { UnrealBloomPass } from './jsm/postprocessing/UnrealBloomPass.js';
import { ShaderPass } from './jsm/postprocessing/ShaderPass.js';
import { OutputPass } from './jsm/postprocessing/OutputPass.js';

const bridge = () => window.ForgeBridge || { onReady() {}, onRevealFinished() {}, onWebGLFailed() {} };
const STAGES = 6;
const MAX_SLABS = 10;
const GLYPHS = '{}<>/=;()01#$*&%';

let R = null; // runtime (everything to dispose)

function col(hex, fb) { try { return new THREE.Color(hex || fb); } catch (e) { return new THREE.Color(fb); } }

function glyphAtlas() {
  const n = GLYPHS.length, cell = 64, c = document.createElement('canvas');
  c.width = cell * n; c.height = cell;
  const g = c.getContext('2d');
  g.fillStyle = '#fff'; g.font = 'bold 44px monospace'; g.textAlign = 'center'; g.textBaseline = 'middle';
  for (let i = 0; i < n; i++) g.fillText(GLYPHS[i], i * cell + cell / 2, cell / 2 + 2);
  const t = new THREE.CanvasTexture(c); t.minFilter = THREE.LinearFilter; return t;
}

function radialTex(inner, outer) {
  const c = document.createElement('canvas'); c.width = c.height = 128;
  const g = c.getContext('2d'); const gr = g.createRadialGradient(64, 64, 0, 64, 64, 64);
  gr.addColorStop(0, inner); gr.addColorStop(1, outer); g.fillStyle = gr; g.fillRect(0, 0, 128, 128);
  return new THREE.CanvasTexture(c);
}

function ringTex() {
  const c = document.createElement('canvas'); c.width = c.height = 256;
  const g = c.getContext('2d'); g.strokeStyle = '#fff'; g.lineWidth = 10; g.shadowBlur = 24; g.shadowColor = '#fff';
  g.beginPath(); g.arc(128, 128, 100, 0, Math.PI * 2); g.stroke();
  return new THREE.CanvasTexture(c);
}

function textSprite(text, color, w = 512, h = 96, font = 34) {
  const c = document.createElement('canvas'); c.width = w; c.height = h;
  const t = new THREE.CanvasTexture(c);
  const m = new THREE.SpriteMaterial({ map: t, transparent: true, depthWrite: false });
  const s = new THREE.Sprite(m);
  s.userData.draw = (txt, glass) => {
    const g = c.getContext('2d'); g.clearRect(0, 0, w, h);
    if (glass) {
      g.fillStyle = 'rgba(23,20,58,0.62)'; g.strokeStyle = 'rgba(255,255,255,0.22)'; g.lineWidth = 2;
      const r = 22; g.beginPath(); g.roundRect ? g.roundRect(2, 2, w - 4, h - 4, r) : g.rect(2, 2, w - 4, h - 4); g.fill(); g.stroke();
    }
    g.fillStyle = color; g.font = `600 ${font}px sans-serif`; g.textAlign = 'center'; g.textBaseline = 'middle';
    g.fillText(String(txt).slice(0, 34), w / 2, h / 2); t.needsUpdate = true;
  };
  s.userData.draw(text, false);
  s.scale.set(w / h * 0.32, 0.32, 1);
  return s;
}

const backVS = `varying vec3 vP; void main(){ vP = normalize(position); gl_Position = projectionMatrix*modelViewMatrix*vec4(position,1.); }`;
const backFS = `uniform vec3 top; uniform vec3 bottom; uniform vec3 neb; uniform float time; varying vec3 vP;
float h(vec3 p){ return fract(sin(dot(p, vec3(12.9898,78.233,37.719)))*43758.5453); }
float n3(vec3 p){ vec3 i=floor(p), f=fract(p); f=f*f*(3.-2.*f);
  return mix(mix(mix(h(i),h(i+vec3(1,0,0)),f.x),mix(h(i+vec3(0,1,0)),h(i+vec3(1,1,0)),f.x),f.y),
             mix(mix(h(i+vec3(0,0,1)),h(i+vec3(1,0,1)),f.x),mix(h(i+vec3(0,1,1)),h(i+vec3(1,1,1)),f.x),f.y),f.z); }
void main(){ float y = vP.y*.5+.5; vec3 c = mix(bottom, top, smoothstep(0.,1.,y));
  float nb = n3(vP*3.+time*.02)*.6 + n3(vP*7.-time*.03)*.4; c += neb * pow(nb, 3.) * .55;
  vec3 sp = floor(vP*260.); float st = step(.9975, h(sp)); c += vec3(st) * (.6+.4*sin(time*2.+h(sp)*40.));
  gl_FragColor = vec4(c,1.); }`;

const floorVS = `varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix*modelViewMatrix*vec4(position,1.); }`;
const floorFS = `uniform vec3 lineCol; uniform vec3 rippleCol; uniform float time; uniform vec3 ripples[6]; uniform float power; varying vec2 vUv;
void main(){ vec2 p = (vUv-.5)*40.; vec2 g = abs(fract(p)-.5); float line = 1.-smoothstep(0.,.04, min(g.x,g.y));
  float d = length(p); float fade = 1.-smoothstep(4., 20., d);
  float rip = 0.; for(int i=0;i<6;i++){ vec3 r = ripples[i]; if(r.z<=0.) continue; float age = time - r.z; if(age<0.||age>3.) continue;
    float rr = age*6.; float w = abs(length(p-r.xy)-rr); rip += (1.-smoothstep(0.,.35,w))*(1.-age/3.); }
  vec3 c = lineCol*line*(.35+.25*power) + rippleCol*rip; gl_FragColor = vec4(c, (line*.6+rip)*fade); }`;

const coreVS = `varying vec3 vN; varying vec3 vV; void main(){ vec4 mv = modelViewMatrix*vec4(position,1.); vN = normalize(normalMatrix*normal); vV = normalize(-mv.xyz); gl_Position = projectionMatrix*mv; }`;
const coreFS = `uniform vec3 inner; uniform vec3 rim; uniform float power; uniform float time; varying vec3 vN; varying vec3 vV;
void main(){ float f = pow(1.-max(dot(vN,vV),0.), 2.2); float pulse = .8+.2*sin(time*3.);
  vec3 c = mix(inner*(.35+.65*power)*pulse, rim, f); gl_FragColor = vec4(c*(1.+power), .55+.45*f); }`;

const glyphVS = `attribute float aG; attribute vec4 aS; uniform float time; uniform float speed; uniform float burst; uniform float pr; varying float vG; varying float vA;
void main(){ vG = aG; float t = fract(aS.w + time*aS.z*speed*.06); float r = mix(5.5, .25, t) + burst*6.*t;
  float a = aS.x + t*9.42 + time*.15; float y = mix(aS.y, 0., t);
  vec3 p = vec3(cos(a)*r, y, sin(a)*r); vA = smoothstep(0.,.15,t)*(1.-smoothstep(.85,1.,t));
  vec4 mv = modelViewMatrix*vec4(p,1.); gl_PointSize = (.42+burst*.6) * pr / -mv.z; gl_Position = projectionMatrix*mv; }`;
const glyphFS = `uniform sampler2D atlas; uniform vec3 c1; uniform vec3 c2; uniform float n; varying float vG; varying float vA;
void main(){ vec2 uv = vec2((vG + gl_PointCoord.x)/n, 1.-gl_PointCoord.y); float a = texture2D(atlas, uv).a; if(a<.05) discard;
  gl_FragColor = vec4(mix(c1,c2,fract(vG*.37)), a*vA); }`;

const slabVS = `varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix*modelViewMatrix*vec4(position,1.); }`;
const slabFS = `uniform float birth; uniform float styled; uniform float shimmer; uniform float dots; uniform float time; uniform vec3 tint; uniform vec3 accent; uniform float alpha; varying vec2 vUv;
float sdRR(vec2 p, vec2 b, float r){ vec2 q = abs(p)-b+r; return length(max(q,0.))+min(max(q.x,q.y),0.)-r; }
void main(){ vec2 p = vUv-.5; float d = sdRR(p, vec2(.5,.5), .08); if(d>0.) discard;
  if(vUv.y < 1.-birth) discard; float scan = smoothstep(.02,0., abs(vUv.y-(1.-birth))) * step(birth,.999);
  vec3 c = tint*.55; float a = .32;
  float rim = smoothstep(-.02,0.,d); c += vec3(rim)*.5; a += rim*.4;
  if(styled>0.){ for(int i=0;i<4;i++){ float y = .72-float(i)*.16; float w = i==0 ? .55 : .8-float(i)*.12;
      float bar = step(abs(vUv.y-y),.028)*step(abs(vUv.x-.5),w*.5); c += accent*bar*.55*styled; a += bar*.25*styled; } }
  if(dots>0.){ for(int i=0;i<3;i++){ float dd = length(vUv-vec2(.07+float(i)*.06,.93)); c += vec3(1.,.5+.2*float(i),.4)*step(dd,.018); } }
  if(shimmer>0.){ float s = smoothstep(.06,0., abs(vUv.x+vUv.y*.4 - fract(time*.35)*1.8+.2)); c += vec3(1.)*s*.45*shimmer; }
  c += accent*scan*2.; gl_FragColor = vec4(c, clamp(a,0.,1.)*alpha); }`;

const arcVS = slabVS;
const arcFS = `uniform float progress; uniform float time; uniform vec3 c1; uniform vec3 c2; varying vec2 vUv;
void main(){ float on = step(vUv.x, progress); float flow = .5+.5*sin(vUv.x*60.-time*6.);
  vec3 c = mix(c1, c2, vUv.x)*(on*(.7+.6*flow)) + vec3(.15)*(1.-on); gl_FragColor = vec4(c, on*.95 + (1.-on)*.18); }`;

const postFS = `uniform sampler2D tDiffuse; uniform float ca; uniform float vig; varying vec2 vUv;
void main(){ vec2 d = vUv-.5; float r = length(d); vec2 o = d*ca*r;
  vec3 c = vec3(texture2D(tDiffuse, vUv+o).r, texture2D(tDiffuse, vUv).g, texture2D(tDiffuse, vUv-o).b);
  c *= 1.-smoothstep(.45, .95, r)*vig; gl_FragColor = vec4(c,1.); }`;
const postVS = `varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix*modelViewMatrix*vec4(position,1.); }`;

function build(theme, opts) {
  const canvas = document.createElement('canvas');
  document.body.appendChild(canvas);
  let renderer;
  try {
    renderer = new THREE.WebGLRenderer({ canvas, antialias: !opts.lowRam, alpha: false, powerPreference: 'high-performance' });
  } catch (e) { canvas.remove(); return null; }
  const maxPR = opts.lowRam ? 1.5 : 2;
  let pr = Math.min(window.devicePixelRatio || 1, maxPR);
  renderer.setPixelRatio(pr);
  renderer.setSize(innerWidth, innerHeight, false);
  renderer.setClearColor(col(theme.background, '#06050E'), 1);

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(55, innerWidth / innerHeight, 0.1, 400);
  camera.position.set(0, 9, 26);
  const disposables = [];
  const track = (o) => { disposables.push(o); return o; };

  const C = {
    bg: col(theme.background, '#06050E'), top: col(theme.backgroundGradientTop, '#15113A'), accent: col(theme.accent, '#FFB547'),
    accent2: col(theme.accentSecondary, '#FF8A3D'), lotus: col(theme.lotus, '#FF6FAE'), lagoon: col(theme.lagoon, '#3FE0D0'),
    surface: col(theme.surface, '#17143A'), text: theme.textPrimary || '#EEE9FF',
  };

  // backdrop
  const back = new THREE.Mesh(track(new THREE.SphereGeometry(180, 48, 32)), track(new THREE.ShaderMaterial({
    vertexShader: backVS, fragmentShader: backFS, side: THREE.BackSide, depthWrite: false,
    uniforms: { top: { value: C.top }, bottom: { value: C.bg }, neb: { value: C.lotus.clone().lerp(C.lagoon, 0.4).multiplyScalar(0.5) }, time: { value: 0 } },
  })));
  scene.add(back);

  // floor grid with ripples
  const ripples = Array.from({ length: 6 }, () => new THREE.Vector3(0, 0, -1));
  const floorMat = track(new THREE.ShaderMaterial({ vertexShader: floorVS, fragmentShader: floorFS, transparent: true, depthWrite: false,
    uniforms: { lineCol: { value: C.lagoon }, rippleCol: { value: C.accent }, time: { value: 0 }, ripples: { value: ripples }, power: { value: 0 } } }));
  const floor = new THREE.Mesh(track(new THREE.PlaneGeometry(40, 40)), floorMat);
  floor.rotation.x = -Math.PI / 2; floor.position.y = -3.2; scene.add(floor);
  let rippleIdx = 0;
  const ripple = (x = 0, z = 0) => { ripples[rippleIdx % 6].set(x, z, R ? R.time : 0); rippleIdx++; };

  // reactor core
  const core = new THREE.Group(); scene.add(core);
  const coreMat = track(new THREE.ShaderMaterial({ vertexShader: coreVS, fragmentShader: coreFS, transparent: true,
    uniforms: { inner: { value: C.accent }, rim: { value: C.lotus }, power: { value: 0.2 }, time: { value: 0 } } }));
  const coreMesh = new THREE.Mesh(track(new THREE.SphereGeometry(1.25, 48, 32)), coreMat); core.add(coreMesh);
  const wire = new THREE.Mesh(track(new THREE.IcosahedronGeometry(1.8, 1)), track(new THREE.MeshBasicMaterial({ color: C.lagoon, wireframe: true, transparent: true, opacity: 0.45 })));
  core.add(wire);
  const rings = [0, 1, 2].map((i) => {
    const m = new THREE.Mesh(track(new THREE.TorusGeometry(2.4 + i * 0.45, 0.025, 8, 120)), track(new THREE.MeshBasicMaterial({ color: i === 1 ? C.accent : C.lotus, transparent: true, opacity: 0.8 })));
    m.rotation.set(Math.random() * 3, Math.random() * 3, 0); core.add(m); return m;
  });
  const halo = new THREE.Sprite(track(new THREE.SpriteMaterial({ map: track(radialTex('rgba(255,181,71,0.9)', 'rgba(255,111,174,0)')), transparent: true, depthWrite: false, blending: THREE.AdditiveBlending })));
  halo.scale.set(9, 9, 1); core.add(halo);

  // glyph particles
  const GN = opts.lowRam ? 900 : 1800;
  const gg = track(new THREE.BufferGeometry());
  const pos = new Float32Array(GN * 3), aG = new Float32Array(GN), aS = new Float32Array(GN * 4);
  for (let i = 0; i < GN; i++) { aG[i] = Math.floor(Math.random() * GLYPHS.length); aS.set([Math.random() * 6.283, (Math.random() - 0.5) * 6, 0.5 + Math.random(), Math.random()], i * 4); }
  gg.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  gg.setAttribute('aG', new THREE.BufferAttribute(aG, 1));
  gg.setAttribute('aS', new THREE.BufferAttribute(aS, 4));
  const glyphMat = track(new THREE.ShaderMaterial({ vertexShader: glyphVS, fragmentShader: glyphFS, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
    uniforms: { atlas: { value: track(glyphAtlas()) }, c1: { value: C.lagoon }, c2: { value: C.accent }, n: { value: GLYPHS.length }, time: { value: 0 }, speed: { value: 1 }, burst: { value: 0 }, pr: { value: pr * innerHeight / 2 } } }));
  const glyphs = new THREE.Points(gg, glyphMat); glyphs.frustumCulled = false; scene.add(glyphs);

  // pooled data beams
  const beams = Array.from({ length: 22 }, () => {
    const g = track(new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(), new THREE.Vector3()]));
    const l = new THREE.Line(g, track(new THREE.LineBasicMaterial({ color: C.lagoon, transparent: true, opacity: 0 })));
    l.userData.life = 0; scene.add(l); return l;
  });
  const fireBeam = (to) => {
    const b = beams.find((x) => x.userData.life <= 0); if (!b) return;
    const p = b.geometry.attributes.position; p.setXYZ(0, 0, 0, 0); p.setXYZ(1, to.x, to.y, to.z); p.needsUpdate = true;
    b.userData.life = 1; b.material.color.copy(Math.random() > 0.5 ? C.lagoon : C.accent);
  };

  // pooled shockwaves
  const rTex = track(ringTex());
  const shocks = Array.from({ length: 4 }, () => {
    const s = new THREE.Sprite(track(new THREE.SpriteMaterial({ map: rTex, color: C.accent, transparent: true, opacity: 0, depthWrite: false, blending: THREE.AdditiveBlending })));
    s.userData.life = 0; scene.add(s); return s;
  });
  const shock = (scale = 1) => { const s = shocks.find((x) => x.userData.life <= 0) || shocks[0]; s.userData.life = 1; s.userData.k = scale; };

  // glass slabs (one per section)
  const slabs = [];
  const slabGeo = track(new THREE.PlaneGeometry(1, 1));
  const addSlab = (i) => {
    const m = track(new THREE.ShaderMaterial({ vertexShader: slabVS, fragmentShader: slabFS, transparent: true, depthWrite: false, side: THREE.DoubleSide,
      uniforms: { birth: { value: 0 }, styled: { value: 0 }, shimmer: { value: 0 }, dots: { value: i === 0 ? 1 : 0 }, time: { value: 0 }, tint: { value: C.surface.clone().lerp(C.lagoon, 0.15) }, accent: { value: C.accent }, alpha: { value: 1 } } }));
    const s = new THREE.Mesh(slabGeo, m);
    const a = -0.9 + (i / Math.max(1, MAX_SLABS - 1)) * 1.8, row = i % 2;
    s.userData.home = new THREE.Vector3(Math.sin(a) * 7.5, 2.2 + row * 2.4 - 1.2, -Math.cos(a) * 7.5 + 2);
    s.position.copy(s.userData.home).multiplyScalar(0.2);
    s.scale.set(3.2, 2, 1); s.lookAt(0, s.userData.home.y, 6);
    s.userData.drift = new THREE.Vector3((Math.random() - 0.5) * 2, (Math.random() - 0.5) * 2, (Math.random() - 0.5) * 2);
    scene.add(s); slabs.push(s); fireBeam(s.userData.home); ripple(s.userData.home.x * 0.5, s.userData.home.z * 0.5);
  };

  // stage ring: 6 nodes + energy arc + labels
  const ring = new THREE.Group(); ring.position.y = -2.6; scene.add(ring);
  const arcMat = track(new THREE.ShaderMaterial({ vertexShader: arcVS, fragmentShader: arcFS, transparent: true, depthWrite: false, side: THREE.DoubleSide,
    uniforms: { progress: { value: 0 }, time: { value: 0 }, c1: { value: C.lagoon }, c2: { value: C.accent } } }));
  const arc = new THREE.Mesh(track(new THREE.RingGeometry(5.0, 5.12, 160, 1, 0, Math.PI * 2)), arcMat);
  // RingGeometry uv: map angle to x for the progress shader
  { const uv = arc.geometry.attributes.uv, p = arc.geometry.attributes.position; for (let i = 0; i < uv.count; i++) { const ang = Math.atan2(p.getY(i), p.getX(i)); uv.setXY(i, ((Math.PI / 2 - ang) / (Math.PI * 2) + 1) % 1, 0.5); } uv.needsUpdate = true; }
  arc.rotation.x = -Math.PI / 2; ring.add(arc);
  const nodeGeo = track(new THREE.SphereGeometry(0.16, 16, 12));
  const nodes = [], labels = [];
  const STAGE_LABELS = (opts.labels && opts.labels.length === STAGES) ? opts.labels : ['Structure', 'Layout', 'Styling', 'Content', 'Motion', 'Finish'];
  for (let i = 0; i < STAGES; i++) {
    const a = (i / STAGES) * Math.PI * 2 - Math.PI / 2;
    const n = new THREE.Mesh(nodeGeo, track(new THREE.MeshBasicMaterial({ color: 0x555577 })));
    n.position.set(Math.cos(a) * 5.06, 0, Math.sin(a) * 5.06); ring.add(n); nodes.push(n);
    const l = textSprite(STAGE_LABELS[i], C.text, 384, 72, 30); track(l.material); track(l.material.map);
    l.position.set(Math.cos(a) * 5.9, 0.45, Math.sin(a) * 5.9); l.material.opacity = 0.55; ring.add(l); labels.push(l);
  }

  // floating glass log cards
  const cards = Array.from({ length: 4 }, (_, i) => {
    const s = textSprite('', C.text, 640, 96, 30); track(s.material); track(s.material.map);
    s.position.set(-5.5 + (i % 2) * 11, 5.2 - Math.floor(i / 2) * 1.0, -3); s.material.opacity = 0; s.scale.set(2.6, 0.39, 1); scene.add(s); return s;
  });
  let cardIdx = 0;
  const pushLog = (txt) => { const s = cards[cardIdx % cards.length]; cardIdx++; s.userData.draw(txt, true); s.material.opacity = 1; s.userData.life = 1; };

  // dust
  const DN = opts.lowRam ? 200 : 500, dg = track(new THREE.BufferGeometry()), dp = new Float32Array(DN * 3);
  for (let i = 0; i < DN; i++) dp.set([(Math.random() - 0.5) * 40, Math.random() * 14 - 3, (Math.random() - 0.5) * 40], i * 3);
  dg.setAttribute('position', new THREE.BufferAttribute(dp, 3));
  const dust = new THREE.Points(dg, track(new THREE.PointsMaterial({ color: C.text, size: 0.05, transparent: true, opacity: 0.5, depthWrite: false })));
  scene.add(dust);

  // post-processing
  const composer = new EffectComposer(renderer);
  composer.setPixelRatio(pr);
  composer.addPass(new RenderPass(scene, camera));
  const bloom = new UnrealBloomPass(new THREE.Vector2(innerWidth / 2, innerHeight / 2), 0.34, 0.42, 0.55);
  composer.addPass(bloom);
  const post = new ShaderPass({ uniforms: { tDiffuse: { value: null }, ca: { value: 0.006 }, vig: { value: 0.55 } }, vertexShader: postVS, fragmentShader: postFS });
  composer.addPass(post);
  composer.addPass(new OutputPass());

  return { renderer, scene, camera, composer, bloom, post, back, floorMat, coreMat, core, wire, rings, halo, glyphMat, glyphs, beams, fireBeam, shocks, shock,
    slabs, addSlab, arcMat, nodes, labels, cards, pushLog, dust, ripple, disposables, canvas, C, maxPR, pr };
}

const Forge = {
  init(themeJson, optsJson) {
    if (R) return true;
    let theme = {}, opts = {};
    try { theme = typeof themeJson === 'string' ? JSON.parse(themeJson) : (themeJson || {}); } catch (e) {}
    try { opts = typeof optsJson === 'string' ? JSON.parse(optsJson) : (optsJson || {}); } catch (e) {}
    const s = build(theme, opts);
    if (!s) { bridge().onWebGLFailed(); return false; }
    R = Object.assign(s, {
      opts, time: 0, last: performance.now(), raf: 0, running: true, dirty: true, reduced: !!opts.reducedMotion,
      target: { stage: 0, progress: 0, sections: 0, logSeq: 0, phase: 'BUILDING' }, power: 0, kick: 0, flyIn: 0,
      drag: { x: 0, y: 0, vx: 0, vy: 0, down: false, lx: 0, ly: 0 }, tilt: { x: 0, y: 0 }, orbit: 0,
      revealing: -1, failed: false, fps: [], slow: 0,
    });
    const onResize = () => {
      if (!R) return;
      R.camera.aspect = innerWidth / innerHeight; R.camera.updateProjectionMatrix();
      R.renderer.setSize(innerWidth, innerHeight, false); R.composer.setSize(innerWidth, innerHeight);
      R.glyphMat.uniforms.pr.value = R.pr * innerHeight / 2; R.dirty = true;
    };
    const onDown = (e) => { const t = e.touches ? e.touches[0] : e; R.drag.down = true; R.drag.lx = t.clientX; R.drag.ly = t.clientY; };
    const onMove = (e) => { if (!R.drag.down) return; const t = e.touches ? e.touches[0] : e;
      R.drag.vx += (t.clientX - R.drag.lx) * 0.004; R.drag.vy += (t.clientY - R.drag.ly) * 0.003; R.drag.lx = t.clientX; R.drag.ly = t.clientY; R.dirty = true; };
    const onUp = () => { R.drag.down = false; };
    const onOrient = (e) => { if (R.reduced || e.gamma == null) return; R.tilt.x = Math.max(-1, Math.min(1, e.gamma / 35)); R.tilt.y = Math.max(-1, Math.min(1, (e.beta - 45) / 40)); };
    const onVis = () => { if (!R) return; R.running = !document.hidden; if (R.running) { R.last = performance.now(); loop(); } };
    R.handlers = { onResize, onDown, onMove, onUp, onOrient, onVis };
    addEventListener('resize', onResize);
    addEventListener('touchstart', onDown, { passive: true }); addEventListener('touchmove', onMove, { passive: true }); addEventListener('touchend', onUp);
    addEventListener('mousedown', onDown); addEventListener('mousemove', onMove); addEventListener('mouseup', onUp);
    addEventListener('deviceorientation', onOrient);
    document.addEventListener('visibilitychange', onVis);
    loop();
    bridge().onReady();
    return true;
  },

  setState(state) {
    if (!R) return;
    let s = state; if (typeof s === 'string') { try { s = JSON.parse(s); } catch (e) { return; } }
    const t = R.target;
    if (typeof s.stage === 'number' && s.stage !== t.stage) {
      if (s.stage > t.stage) { R.kick = 1; R.shock(0.8); R.ripple(0, 0); }
      t.stage = s.stage;
    }
    if (typeof s.progress === 'number') t.progress = s.progress / 100;
    const want = Math.min(MAX_SLABS, s.sections | 0);
    while (R.slabs.length < want) R.addSlab(R.slabs.length);
    if (Array.isArray(s.log) && typeof s.logSeq === 'number' && s.logSeq > t.logSeq) {
      const fresh = s.log.slice(-Math.min(4, s.logSeq - t.logSeq)); fresh.forEach((l) => R.pushLog(l)); t.logSeq = s.logSeq;
    }
    if (typeof s.chars === 'number' && R.slabs.length === 0 && s.chars > 2500) R.addSlab(0);
    if (s.phase) t.phase = s.phase;
    R.dirty = true;
  },

  reveal() {
    if (!R || R.revealing >= 0) return;
    R.revealing = 0; R.dirty = true;
    const f = document.getElementById('flash');
    if (f) { f.style.transition = 'none'; f.style.opacity = '0.95'; requestAnimationFrame(() => { f.style.transition = 'opacity 900ms ease'; f.style.opacity = '0'; }); }
    R.shock(1.6); R.shock(1.0); R.ripple(0, 0); R.ripple(2, 1);
    if (R.reduced) { setTimeout(() => bridge().onRevealFinished(), 350); R.revealing = 2; }
    // slow phones: the reveal must always end (max 2.6 s), never hang on the build screen
    setTimeout(() => { if (R && R.revealing >= 0 && R.revealing < 2) { R.revealing = 2; bridge().onRevealFinished(); } }, 2600);
  },

  fail() {
    if (!R) return;
    R.failed = true; document.body.classList.add('failed'); R.dirty = true;
  },

  dispose() {
    if (!R) return;
    cancelAnimationFrame(R.raf); R.running = false;
    const h = R.handlers;
    removeEventListener('resize', h.onResize); removeEventListener('touchstart', h.onDown); removeEventListener('touchmove', h.onMove);
    removeEventListener('touchend', h.onUp); removeEventListener('mousedown', h.onDown); removeEventListener('mousemove', h.onMove);
    removeEventListener('mouseup', h.onUp); removeEventListener('deviceorientation', h.onOrient); document.removeEventListener('visibilitychange', h.onVis);
    R.disposables.forEach((d) => { try { d.dispose(); } catch (e) {} });
    try { R.composer.dispose && R.composer.dispose(); R.bloom.dispose && R.bloom.dispose(); } catch (e) {}
    R.renderer.dispose(); R.renderer.forceContextLoss && R.renderer.forceContextLoss(); R.canvas.remove();
    document.body.classList.remove('failed');
    R = null;
  },
};
window.Forge = Forge;

function easeOut(x) { return 1 - Math.pow(1 - x, 3); }

function loop() {
  if (!R || !R.running) return;
  R.raf = requestAnimationFrame(loop);
  const now = performance.now(); let dt = Math.min(0.05, (now - R.last) / 1000); R.last = now;
  if (R.reduced) { if (!R.dirty && (R.revealing < 0 || R.revealing >= 2)) return; dt = 0; }
  R.dirty = false;
  R.time += dt;
  const t = R.time, T = R.target;

  // adaptive quality: lower pixel ratio when the phone can't keep up
  R.fps.push(dt); if (R.fps.length > 90) R.fps.shift();
  if (R.fps.length === 90 && !R.reduced) {
    const avg = R.fps.reduce((a, b) => a + b, 0) / 90;
    if (avg > 1 / 40 && R.pr > 1) { R.pr = Math.max(1, R.pr - 0.25); R.renderer.setPixelRatio(R.pr); R.composer.setPixelRatio(R.pr); R.fps.length = 0; }
  }

  const powerTarget = R.failed ? 0.05 : 0.25 + T.progress * 0.75;
  R.power += (powerTarget - R.power) * Math.min(1, dt * 2);
  R.kick = Math.max(0, R.kick - dt * 1.6);

  [R.back.material, R.floorMat, R.coreMat, R.glyphMat, R.arcMat].forEach((m) => { m.uniforms.time.value = t; });
  R.floorMat.uniforms.power.value = R.power;
  R.coreMat.uniforms.power.value = R.power;
  R.arcMat.uniforms.progress.value += (T.progress - R.arcMat.uniforms.progress.value) * Math.min(1, dt * 3 + (R.reduced ? 1 : 0));
  R.glyphMat.uniforms.speed.value = R.failed ? 0.15 : 0.8 + R.power * 1.6;
  R.bloom.strength = R.failed ? 0.12 : 0.34 + R.power * 0.14;

  R.core.rotation.y += dt * (0.3 + R.power); R.wire.rotation.x += dt * 0.4; R.wire.rotation.z -= dt * 0.25;
  R.rings.forEach((r, i) => { r.rotation.x += dt * (0.5 + i * 0.3); r.rotation.y += dt * (0.3 + i * 0.2); });
  const hs = 8 + R.power * 3 + Math.sin(t * 3) * 0.4; R.halo.scale.set(hs, hs, 1);
  R.halo.material.opacity = R.failed ? 0.25 : 0.9;

  R.nodes.forEach((n, i) => { const on = i <= T.stage; n.material.color.copy(on ? (i === T.stage ? R.C.accent : R.C.lagoon) : new THREE.Color(0x444466));
    const k = i === T.stage ? 1.4 + Math.sin(t * 5) * 0.2 : 1; n.scale.setScalar(k); R.labels[i].material.opacity = on ? 1 : 0.45; });

  R.slabs.forEach((s, i) => {
    const u = s.material.uniforms; u.time.value = t;
    u.birth.value = Math.min(1, u.birth.value + dt * 0.9 + (R.reduced ? 1 : 0));
    u.styled.value = Math.min(1, u.styled.value + (T.stage >= 2 ? dt * 1.5 : 0) + (R.reduced && T.stage >= 2 ? 1 : 0));
    u.shimmer.value = T.stage >= 4 ? 1 : 0;
    if (R.revealing < 0) {
      const home = s.userData.home, bob = Math.sin(t * 0.8 + i) * 0.12;
      const target = R.failed ? home.clone().add(s.userData.drift.clone().multiplyScalar(1 + t * 0.02)) : home;
      s.position.lerp(new THREE.Vector3(target.x, target.y + bob, target.z), Math.min(1, dt * 2.5 + (R.reduced ? 1 : 0)));
    }
  });

  R.beams.forEach((b) => { if (b.userData.life > 0) { b.userData.life -= dt * 1.6; b.material.opacity = Math.max(0, b.userData.life); } });
  if (!R.failed && R.revealing < 0 && Math.random() < dt * (1 + R.power * 4) && R.slabs.length) R.fireBeam(R.slabs[(Math.random() * R.slabs.length) | 0].position);
  R.shocks.forEach((s) => { if (s.userData.life > 0) { s.userData.life -= dt * 1.2; const k = (1 - s.userData.life) * 22 * (s.userData.k || 1); s.scale.set(k, k, 1); s.material.opacity = Math.max(0, s.userData.life); } });
  R.cards.forEach((c) => { if (c.userData.life > 0) { c.userData.life -= dt * 0.22; c.material.opacity = Math.min(1, c.userData.life * 2); c.position.y += dt * 0.08; } });
  R.dust.rotation.y += dt * 0.01;

  // camera: fly-in, slow orbit, drag, tilt parallax, stage kick
  R.flyIn = Math.min(1, R.flyIn + dt / 2 + (R.reduced ? 1 : 0));
  const fe = easeOut(R.flyIn);
  R.drag.vx *= 0.92; R.drag.vy *= 0.92;
  R.orbit += dt * 0.06 + R.drag.vx * dt * 6;
  const dist = 26 - fe * 12 - R.kick * 1.5, el = 9 - fe * 4.5 + R.drag.vy * 2 + R.tilt.y * 1.2;
  R.camera.position.set(Math.sin(R.orbit + R.tilt.x * 0.25) * dist, el, Math.cos(R.orbit + R.tilt.x * 0.25) * dist);
  if (R.kick > 0 && !R.reduced) { R.camera.position.x += (Math.random() - 0.5) * R.kick * 0.12; R.camera.position.y += (Math.random() - 0.5) * R.kick * 0.12; }
  R.camera.fov = 55 + R.kick * 4;
  R.camera.lookAt(0, 0.6, 0);

  // reveal: slabs fuse into one panel that flies into the camera with an FOV punch
  if (R.revealing >= 0 && R.revealing < 2) {
    R.revealing = Math.min(1.5, R.revealing + dt) ;
    const k = R.revealing / 1.5;
    R.glyphMat.uniforms.burst.value = easeOut(Math.min(1, k * 2));
    const front = new THREE.Vector3().copy(R.camera.position).lerp(new THREE.Vector3(0, 0.6, 0), 0.92 - easeOut(k) * 0.9);
    R.slabs.forEach((s) => { s.position.lerp(front, Math.min(1, dt * (3 + k * 10))); s.lookAt(R.camera.position); const sc = 3.2 + easeOut(k) * 9; s.scale.set(sc, sc * 0.62, 1); });
    R.camera.fov = 55 + Math.sin(k * Math.PI) * 18;
    if (k >= 1) { R.revealing = 2; bridge().onRevealFinished(); }
  }
  R.camera.updateProjectionMatrix();
  R.composer.render(dt);
}
