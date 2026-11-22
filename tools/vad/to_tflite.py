"""Silero VAD v5 (MIT, snakers4/silero-vad), 16 kHz branch, rebuilt as a plain TensorFlow function with explicit LSTM
state, its weights copied from the published silero_vad.onnx, and converted to LiteRT (.tflite). Checked against the
ONNX model frame by frame. Usage: python to_tflite.py <dir with silero_vad.onnx> [out.tflite]"""
import sys, numpy as np, tensorflow as tf, importlib.util
d=sys.argv[1]; out=sys.argv[2] if len(sys.argv)>2 else d+'/silero_vad_16k.tflite'
spec=importlib.util.spec_from_file_location('ref', __file__.replace('to_tflite.py','ref.py')); ref=importlib.util.module_from_spec(spec)
sys.argv=[sys.argv[0], d]; spec.loader.exec_module(ref)
W=ref.W
def k(name): return tf.constant(W[name])
basis=tf.constant(np.transpose(W['stft.forward_basis_buffer'],(2,1,0)))  # [256,1,258] (k, in, out)
def cw(name): return tf.constant(np.transpose(W[name],(2,1,0)))           # [K,Cin,Cout]
enc=[(cw(f'encoder.{i}.reparam_conv.weight'),k(f'encoder.{i}.reparam_conv.bias'),s) for i,s in [(0,1),(1,2),(2,2),(3,1)]]
wih=tf.constant(W['decoder.rnn.weight_ih'].T); whh=tf.constant(W['decoder.rnn.weight_hh'].T)
b=tf.constant(W['decoder.rnn.bias_ih']+W['decoder.rnn.bias_hh'])
wout=tf.constant(W['decoder.decoder.2.weight'][0,:,0][:,None]); bout=tf.constant(W['decoder.decoder.2.bias'])

@tf.function(input_signature=[tf.TensorSpec([1,576],tf.float32),tf.TensorSpec([1,128],tf.float32),tf.TensorSpec([1,128],tf.float32)])
def silero(x,h,c):
    # The last 64 samples reflected (no edge repeat), as torch's reflect pad.
    tail=tf.reverse(x[:,-65:-1],axis=[1])
    xp=tf.concat([x,tail],axis=1)[:,:,None]                 # [1,640,1]
    sp=tf.nn.conv1d(xp,basis,stride=128,padding='VALID')    # [1,4,258]
    mag=tf.sqrt(sp[:,:,:129]**2+sp[:,:,129:]**2)            # [1,4,129]
    y=mag
    for w,bb,s in enc:
        y=tf.nn.relu(tf.nn.conv1d(tf.pad(y,[[0,0],[1,1],[0,0]]),w,stride=s,padding='VALID')+bb)
    e=y[:,0,:]                                               # [1,128]
    g=tf.matmul(e,wih)+tf.matmul(h,whh)+b
    i,f,gg,o=tf.split(g,4,axis=1)
    c2=tf.sigmoid(f)*c+tf.sigmoid(i)*tf.tanh(gg); h2=tf.sigmoid(o)*tf.tanh(c2)
    p=tf.sigmoid(tf.matmul(tf.nn.relu(h2),wout)+bout)        # [1,1]
    return {'probability':p,'h':h2,'c':c2}

conv=tf.lite.TFLiteConverter.from_concrete_functions([silero.get_concrete_function()])
conv.target_spec.supported_ops=[tf.lite.OpsSet.TFLITE_BUILTINS]
open(out,'wb').write(conv.convert())
# Check against ONNX Runtime on the noisy fixtures.
import onnxruntime as ort, wave, os, time
s=ort.InferenceSession(d+'/silero_vad.onnx')
it=tf.lite.Interpreter(model_path=out); it.allocate_tensors()
ins={t['name']:t['index'] for t in it.get_input_details()}
od=it.get_output_details()
print('inputs', [(t['name'],t['index'],list(t['shape'])) for t in it.get_input_details()])
print('outputs', [(t['name'],t['index'],list(t['shape'])) for t in od])
def run(x,h,c):
    it.set_tensor(ins['x'],x); it.set_tensor(ins['h'],h); it.set_tensor(ins['c'],c); it.invoke()
    v=[it.get_tensor(t['index']) for t in od]
    # TF orders a dict's outputs by key: c, h, probability.
    return {'c':v[0],'h':v[1],'probability':v[2]}
worst=0; n=0; t0=time.time()
for name in ['sentence_0db','pause_0db','short_5db']:
    w=wave.open(f'{d}/{name}.wav'); x=np.frombuffer(w.readframes(w.getnframes()),np.int16).astype(np.float32)/32768
    st=np.zeros((2,1,128),np.float32); h=np.zeros((1,128),np.float32); c=np.zeros((1,128),np.float32); ctx=np.zeros(64,np.float32)
    for k0 in range(0,len(x)-512+1,512):
        chunk=x[k0:k0+512]; inp=np.concatenate([ctx,chunk])[None].astype(np.float32)
        p1,st=s.run(None,{'input':inp,'state':st,'sr':np.array(16000,np.int64)})
        r=run(inp,h,c); h=r['h']; c=r['c']; n+=1
        assert np.allclose(h,st[0],atol=1e-3) and np.allclose(c,st[1],atol=1e-3), 'state order'
        worst=max(worst,abs(float(p1[0][0])-float(r['probability'][0][0])))
        ctx=chunk[-64:]
print('frames',n,'worst probability difference vs ONNX', worst)
print('tflite size', os.path.getsize(out))
