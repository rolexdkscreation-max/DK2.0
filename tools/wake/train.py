# openWakeWord जैसा छोटा classifier: [1,16,96] -> [1,1] (sigmoid)
import os, sys, numpy as np, torch, torch.nn as nn
torch.manual_seed(0); np.random.seed(0)
FD = os.path.expanduser("~/wk/feat"); OUT = os.path.expanduser("~/wk/out"); os.makedirs(OUT, exist_ok=True)
word = sys.argv[1]; other = "hello" if word == "hey" else "hey"
ld = lambda n: np.load(f"{FD}/{n}.npy").astype(np.float32)
pos = ld(f"train_{word}")
negs = [ld(f"train_{other}"), ld("train_neg"), ld("train_speechneg")]
acav = np.load(os.path.expanduser("~/wk/data/acav_part.npy"), mmap_mode="r") if False else None
raw = np.array(np.memmap(os.path.expanduser("~/wk/data/acav_part.npy"), dtype=np.float16, mode="r", offset=128, shape=(499999, 16, 96)))  # RAM में (तेज़)
# गलत जागना कम: openWakeWord के validation features का *पहला आधा* भी negative (दूसरा आधा सिर्फ़ जाँच के लिए, कभी ट्रेनिंग में नहीं)
vf = np.load(os.path.expanduser("~/wk/data/validation_set_features.npy"), mmap_mode="r")
half = len(vf) // 2
vw = np.lib.stride_tricks.sliding_window_view(np.asarray(vf[:half]), (16, 96))[::3, 0].astype(np.float32)
real = np.concatenate([ld("train_speechneg"), ld("train_libri"), vw])   # असली बोली
hard = np.concatenate(negs)                                              # TTS के मिलते-जुलते शब्द + दूसरा वेक वर्ड
print("pos", pos.shape, "hard neg", hard.shape, "real", real.shape, flush=True)

class Net(nn.Module):
    def __init__(s, n=64):
        super().__init__()
        s.f = nn.Flatten(); s.l1 = nn.Linear(16 * 96, n); s.n1 = nn.LayerNorm(n); s.l2 = nn.Linear(n, n); s.n2 = nn.LayerNorm(n); s.o = nn.Linear(n, 1)
    def forward(s, x):
        x = torch.relu(s.n1(s.l1(s.f(x)))); x = torch.relu(s.n2(s.l2(x))); return torch.sigmoid(s.o(x))
net = Net(); opt = torch.optim.Adam(net.parameters(), lr=1e-3)
steps = int(sys.argv[2]) if len(sys.argv) > 2 else 12000
bce = nn.BCELoss(reduction="none")
for it in range(steps):
    ip = np.random.randint(0, len(pos), 128); ih = np.random.randint(0, len(hard), 96); ir = np.random.randint(0, len(real), 160)
    ia = np.random.randint(0, len(raw), 128)
    xb = np.concatenate([pos[ip], hard[ih], real[ir], np.asarray(raw[ia], np.float32)])
    yb = np.concatenate([np.ones(128), np.zeros(96 + 160 + 128)]).astype(np.float32)
    # negative weight बढ़ता जाता है (कम गलत जागना) - openWakeWord जैसा
    wneg = 1 + 19 * min(1.0, it / (steps * 0.7))
    wb = np.concatenate([np.ones(128), np.full(96 + 160 + 128, wneg)]).astype(np.float32)
    x = torch.from_numpy(xb); y = torch.from_numpy(yb)[:, None]; w = torch.from_numpy(wb)[:, None]
    p = net(x); loss = (bce(p, y) * w).mean()
    opt.zero_grad(); loss.backward(); opt.step()
    if it == int(steps * 0.75):
        for g in opt.param_groups: g["lr"] = 2e-4
    if it % 2000 == 0: print(it, float(loss.detach()), flush=True)
net.eval()
torch.save(net.state_dict(), f"{OUT}/{word}_dk.pt")
torch.onnx.export(net, torch.zeros(1, 16, 96), f"{OUT}/{word}_dk.onnx", input_names=["input"], output_names=["score"], opset_version=13, dynamic_axes={"input": {0: "b"}, "score": {0: "b"}}, dynamo=False)
print("saved", f"{OUT}/{word}_dk.onnx")
