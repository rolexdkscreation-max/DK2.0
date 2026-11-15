// 15 नवंबर 2026: DK का 3D कैरेक्टर. .glb और GLTFLoader इंटरनेट से डाउनलोड होकर /models/ से आते हैं (APK में नहीं).
import * as THREE from 'three';
import { GLTFLoader } from '/models/lib/loaders/GLTFLoader.js';

const bridge = window.DKChar || { onReady() {}, onError() {}, onTap() {} };
let renderer, scene, camera, mixer, model, clips = [], action = null, idx = 0;
let yaw = 0, dragX = null, talking = false, t0 = performance.now();
const clock = new THREE.Clock();
const PREFER = ['Wave', 'Idle', 'Dance', 'Survey', 'Walking', 'Walk', 'Run'];

function fail(m) { try { bridge.onError(String(m)); } catch (e) {} }

function init() {
  try {
    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'low-power' });
  } catch (e) { fail('WebGL नहीं चला'); return false; }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setSize(innerWidth, innerHeight);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  document.body.appendChild(renderer.domElement);
  scene = new THREE.Scene();
  camera = new THREE.PerspectiveCamera(35, innerWidth / innerHeight, 0.01, 100);
  scene.add(new THREE.HemisphereLight(0xffffff, 0x334466, 2.2));
  const d = new THREE.DirectionalLight(0xffffff, 2.0); d.position.set(2, 4, 3); scene.add(d);
  addEventListener('resize', () => { camera.aspect = innerWidth / innerHeight; camera.updateProjectionMatrix(); renderer.setSize(innerWidth, innerHeight); });
  const el = renderer.domElement;
  el.addEventListener('pointerdown', e => { dragX = e.clientX; t0 = performance.now(); });
  el.addEventListener('pointermove', e => { if (dragX !== null) { yaw += (e.clientX - dragX) * 0.01; dragX = e.clientX; } });
  el.addEventListener('pointerup', e => { if (performance.now() - t0 < 250) { try { bridge.onTap(); } catch (x) {} } dragX = null; });
  renderer.setAnimationLoop(loop);
  return true;
}

function fit(obj) {
  const box = new THREE.Box3().setFromObject(obj);
  const size = box.getSize(new THREE.Vector3()), c = box.getCenter(new THREE.Vector3());
  const s = 1.6 / Math.max(size.x, size.y, size.z, 1e-6);
  obj.scale.multiplyScalar(s);
  obj.position.sub(c.multiplyScalar(s));
  camera.position.set(0, 0.1, 3.4); camera.lookAt(0, 0, 0);
}

function play(i) {
  if (!mixer || clips.length === 0) return '';
  idx = ((i % clips.length) + clips.length) % clips.length;
  const next = mixer.clipAction(clips[idx]);
  if (action && action !== next) action.fadeOut(0.3);
  next.reset().fadeIn(0.3).play();
  action = next;
  return clips[idx].name;
}

function loop() {
  const dt = clock.getDelta();
  if (mixer) mixer.update(dt);
  if (model) {
    model.rotation.y += (yaw - model.rotation.y) * 0.15;
    const k = talking ? 1 + Math.sin(performance.now() / 90) * 0.02 : 1;
    model.scale.setScalar(model.userData.base * k);
  }
  renderer.render(scene, camera);
}

window.Char = {
  load(file) {
    if (!renderer && !init()) return;
    if (model) { scene.remove(model); model = null; mixer = null; clips = []; action = null; }
    new GLTFLoader().load('/models/' + file, g => {
      model = g.scene; fit(model); model.userData.base = model.scale.x; scene.add(model);
      clips = g.animations || [];
      if (clips.length) {
        mixer = new THREE.AnimationMixer(model);
        let start = 0;
        for (const p of PREFER) { const j = clips.findIndex(c => c.name.toLowerCase() === p.toLowerCase()); if (j >= 0) { start = j; break; } }
        play(start);
      }
      try { bridge.onReady(JSON.stringify(clips.map(c => c.name))); } catch (e) {}
    }, undefined, e => fail((e && e.message) || 'फ़ाइल नहीं खुली'));
  },
  next() { return play(idx + 1); },
  playName(n) { const j = clips.findIndex(c => c.name.toLowerCase().includes(String(n).toLowerCase())); return j >= 0 ? play(j) : ''; },
  talk(on) { talking = !!on; },
};
