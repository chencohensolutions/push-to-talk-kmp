import onnx, numpy as np, sys
from onnx import numpy_helper
d=sys.argv[1]
m=onnx.load(d+'/silero_vad.onnx')
iff=[n for n in m.graph.node if n.op_type=='If'][0]
g=[a.g for a in iff.attribute if a.name=='then_branch'][0]
P='If_0_then_branch__Inline_0__'
W={}
for n in g.node:
    if n.op_type=='Constant':
        name=n.output[0].replace(P,'')
        if name.startswith(('stft.forward','encoder.','decoder.')): W[name]=numpy_helper.to_array(n.attribute[0].t).astype(np.float32)
def conv1d(x,w,b,stride,pad):
    # x [Cin,T], w [Cout,Cin,K]
    if pad: x=np.pad(x,((0,0),(pad,pad)))
    K=w.shape[2]; T=(x.shape[1]-K)//stride+1
    out=np.zeros((w.shape[0],T),np.float32)
    for t in range(T):
        out[:,t]=np.tensordot(w,x[:,t*stride:t*stride+K],axes=([1,2],[0,1]))
    return out+(b[:,None] if b is not None else 0)
def forward(x576,state):
    x=np.pad(x576,(0,64),mode='reflect')           # [640]
    spec=conv1d(x[None,:],W['stft.forward_basis_buffer'],None,128,0)  # [258,4]
    mag=np.sqrt(spec[:129]**2+spec[129:]**2)
    h=np.maximum(conv1d(mag,W['encoder.0.reparam_conv.weight'],W['encoder.0.reparam_conv.bias'],1,1),0)
    h=np.maximum(conv1d(h,W['encoder.1.reparam_conv.weight'],W['encoder.1.reparam_conv.bias'],2,1),0)
    h=np.maximum(conv1d(h,W['encoder.2.reparam_conv.weight'],W['encoder.2.reparam_conv.bias'],2,1),0)
    h=np.maximum(conv1d(h,W['encoder.3.reparam_conv.weight'],W['encoder.3.reparam_conv.bias'],1,1),0)  # [128,1]
    xin=h[:,0]; hp=state[0,0]; cp=state[1,0]
    gates=W['decoder.rnn.weight_ih']@xin+W['decoder.rnn.bias_ih']+W['decoder.rnn.weight_hh']@hp+W['decoder.rnn.bias_hh']
    i,f,gg,o=np.split(gates,4)
    sig=lambda z:1/(1+np.exp(-z))
    c=sig(f)*cp+sig(i)*np.tanh(gg); hn=sig(o)*np.tanh(c)
    p=sig(W['decoder.decoder.2.weight'][0,:,0]@np.maximum(hn,0)+W['decoder.decoder.2.bias'][0])
    return float(p), np.stack([hn[None],c[None]])
if __name__=='__main__':
    import onnxruntime as ort
    s=ort.InferenceSession(d+'/silero_vad.onnx')
    rng=np.random.default_rng(1); st=np.zeros((2,1,128),np.float32); st2=st.copy(); mx=0; mxs=0
    import wave
    w=wave.open(d+'/pause_0db.wav'); x=np.frombuffer(w.readframes(w.getnframes()),np.int16).astype(np.float32)/32768
    ctx=np.zeros(64,np.float32)
    for k in range(0,len(x)-512,512):
        chunk=x[k:k+512]; inp=np.concatenate([ctx,chunk])
        p1,st=s.run(None,{'input':inp[None],'state':st,'sr':np.array(16000,np.int64)})
        p2,st2=forward(inp,st2)
        mx=max(mx,abs(float(p1[0][0])-p2)); mxs=max(mxs,np.abs(st-st2).max()); ctx=chunk[-64:]
    print('max prob diff', mx, 'max state diff', mxs)
    # export weights for Kotlin: little-endian float32 in fixed order
    order=['stft.forward_basis_buffer','encoder.0.reparam_conv.weight','encoder.0.reparam_conv.bias','encoder.1.reparam_conv.weight','encoder.1.reparam_conv.bias','encoder.2.reparam_conv.weight','encoder.2.reparam_conv.bias','encoder.3.reparam_conv.weight','encoder.3.reparam_conv.bias','decoder.rnn.weight_ih','decoder.rnn.weight_hh','decoder.rnn.bias_ih','decoder.rnn.bias_hh','decoder.decoder.2.weight','decoder.decoder.2.bias']
    with open(d+'/silero_vad_16k.bin','wb') as f:
        for k in order: f.write(W[k].astype('<f4').tobytes()); print(k, W[k].shape)
