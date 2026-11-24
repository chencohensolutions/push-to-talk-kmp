import onnx, numpy as np, tensorflow as tf, cv2, onnxruntime as ort
from onnx import numpy_helper, helper
S=384
m=onnx.load('lines384.onnx')
init={i.name:numpy_helper.to_array(i).astype(np.float32) for i in m.graph.initializer}
nodes=list(m.graph.node)
def build(x):
    env={'image_cast_to_/model0/model0.0/Pad':x}
    for n in nodes[1:-1]:
        a={k.name:helper.get_attribute_value(k) for k in n.attribute}
        i0=env.get(n.input[0])
        if n.op_type=='Pad':
            p=init[n.input[1]].astype(int)  # onnx: [x1b,x2b,x3b,x4b,x1e,...] NCHW
            ph,pw=p[2],p[3]
            y=tf.pad(i0,[[0,0],[ph,ph],[pw,pw],[0,0]],mode='REFLECT')
        elif n.op_type=='Conv':
            W=init[n.input[1]].transpose(2,3,1,0); b=init[n.input[2]]
            pd=a.get('pads',[0,0,0,0]); s=a['strides'][0]
            if pd[0]>0: i0=tf.pad(i0,[[0,0],[pd[0],pd[2]],[pd[1],pd[3]],[0,0]])
            y=tf.nn.conv2d(i0,W,s,'VALID')+b
        elif n.op_type=='InstanceNormalization':
            g=init[n.input[1]]; b=init[n.input[2]]
            mu,var=tf.nn.moments(i0,[1,2],keepdims=True)
            y=(i0-mu)*tf.math.rsqrt(var+1e-5)*g+b
        elif n.op_type=='Relu': y=tf.nn.relu(i0)
        elif n.op_type=='Add': y=i0+env[n.input[1]]
        elif n.op_type=='ConvTranspose':
            W=init[n.input[1]].transpose(2,3,1,0)  # (in,out,kh,kw)->(kh,kw,out,in)
            b=init[n.input[2]]; sh=tf.shape(i0)
            y=tf.nn.conv2d_transpose(i0,W,[sh[0],sh[1]*2+1,sh[2]*2+1,W.shape[2]],2,'VALID')[:,1:,1:,:]+b
        elif n.op_type=='Sigmoid': y=tf.sigmoid(i0)
        else: raise Exception(n.op_type)
        env[n.output[0]]=y
    return y
@tf.function(input_signature=[tf.TensorSpec([1,S,S,3],tf.float32)])
def f(x): return build(x)
x=cv2.resize(cv2.imread('crop.png'),(S,S))[:,:,::-1].astype(np.float32)/255.
ref=ort.InferenceSession('lines384.onnx').run(None,{'image':x.transpose(2,0,1)[None]})[0][0,0]
got=f(tf.constant(x[None])).numpy()[0,...,0]
print('max diff vs onnx',np.abs(ref-got).max(), np.abs(ref-got).mean(), ref.mean(), got.mean())
conv=tf.lite.TFLiteConverter.from_concrete_functions([f.get_concrete_function()],f)
conv.optimizations=[tf.lite.Optimize.DEFAULT]; conv.target_spec.supported_types=[tf.float16]
open('style_ink.tflite','wb').write(conv.convert())
from ai_edge_litert.interpreter import Interpreter
it=Interpreter('style_ink.tflite'); it.allocate_tensors()
it.set_tensor(it.get_input_details()[0]['index'],x[None]); it.invoke()
y=it.get_tensor(it.get_output_details()[0]['index'])[0,...,0]
print('tflite diff',np.abs(ref-y).max())
cv2.imwrite('tfl_ink.png',(np.clip(y,0,1)*255).astype(np.uint8))
