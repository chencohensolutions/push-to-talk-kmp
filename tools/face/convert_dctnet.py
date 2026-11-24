import tensorflow as tf, numpy as np, cv2, sys
M='/private/tmp/claude-501/-Users-hencohen-Projects/2e124cc9-f62f-48e8-ac35-47f0042fdaa8/scratchpad/avatar-models/'
S=int(sys.argv[1]) if len(sys.argv)>1 else 384
for name,pb in [('soft','dct3d/cartoon_h.pb'),('painted','dctdesign/cartoon_h.pb'),('handdrawn','dcthand/cartoon_h.pb')]:
    conv=tf.compat.v1.lite.TFLiteConverter.from_frozen_graph(M+pb,['input_image'],['output_image'],input_shapes={'input_image':[S,S,3]})
    conv.optimizations=[tf.lite.Optimize.DEFAULT]
    conv.target_spec.supported_types=[tf.float16]
    open(f'style_{name}.tflite','wb').write(conv.convert())
    from ai_edge_litert.interpreter import Interpreter
    it=Interpreter(f'style_{name}.tflite'); it.allocate_tensors()
    di=it.get_input_details()[0]; do=it.get_output_details()[0]
    x=cv2.resize(cv2.imread('crop.png'),(S,S)).astype(np.float32)
    import time; t=time.time(); it.set_tensor(di['index'],x); it.invoke(); y=it.get_tensor(do['index']); dt=time.time()-t
    print(name,di['shape'],do['shape'],do['dtype'],'%.2fs'%dt)
    cv2.imwrite(f'tfl_{name}.png',np.clip(y,0,255).astype(np.uint8))
