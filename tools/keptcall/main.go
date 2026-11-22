// Makes a kept-call fixture as ptt-server's recorder writes one, with frames encrypted as LiveKit's frame cryptor
// does (an implementation independent of the app's LiveKitFrames, to check it against).
package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/pbkdf2"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"os"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

type frame struct {
	us  uint64
	key bool
	b   []byte
}

func ivf(path string, fps uint64) []frame {
	d, err := os.ReadFile(path)
	must(err)
	var out []frame
	for at := 32; at+12 <= len(d); {
		n := int(binary.LittleEndian.Uint32(d[at:]))
		pts := binary.LittleEndian.Uint64(d[at+4:])
		b := d[at+12 : at+12+n]
		out = append(out, frame{pts * 1000000 / fps, b[0]&1 == 0, b})
		at += 12 + n
	}
	return out
}

func ogg(path string) []frame {
	d, err := os.ReadFile(path)
	must(err)
	var packets [][]byte
	var cur []byte
	for at := 0; at+27 <= len(d); {
		segs := int(d[at+26])
		lace := d[at+27 : at+27+segs]
		body := at + 27 + segs
		for _, l := range lace {
			cur = append(cur, d[body:body+int(l)]...)
			body += int(l)
			if l < 255 {
				packets = append(packets, cur)
				cur = nil
			}
		}
		at = body
	}
	var out []frame
	for i, p := range packets[2:] {
		out = append(out, frame{uint64(i) * 20000, true, p})
	}
	return out
}

func main() {
	rawKey := make([]byte, 32)
	for i := range rawKey {
		rawKey[i] = byte(i*7 + 1)
	}
	material := base64.StdEncoding.EncodeToString(rawKey)
	key, err := pbkdf2.Key(sha256.New, material, []byte("LKFrameEncryptionKey"), 100000, 16)
	must(err)
	block, _ := aes.NewCipher(key)
	gcm, _ := cipher.NewGCM(block)
	type track struct {
		Blob, Identity, Kind, Codec, RecordingStartedAt string
		Size                                            int
	}
	var tracks []track
	n := uint32(0)
	write := func(name, identity, kind, codec, started string, frames []frame, clear func(frame) int) {
		var out []byte
		for _, f := range frames {
			n++
			iv := make([]byte, 12)
			binary.BigEndian.PutUint32(iv, 0xabcdef01)
			binary.BigEndian.PutUint32(iv[4:], n)
			binary.BigEndian.PutUint32(iv[8:], n*3)
			c := clear(f)
			enc := append([]byte{}, f.b[:c]...)
			enc = gcm.Seal(enc, iv, f.b[c:], f.b[:c])
			enc = append(enc, iv...)
			enc = append(enc, 12, 0)
			rec := make([]byte, 4+8+1)
			binary.BigEndian.PutUint32(rec, uint32(9+len(enc)))
			binary.BigEndian.PutUint64(rec[4:], f.us)
			if f.key {
				rec[12] = 1
			}
			out = append(out, rec...)
			out = append(out, enc...)
		}
		must(os.WriteFile(name+".blob", out, 0o644))
		tracks = append(tracks, track{name, identity, kind, codec, started, len(out)})
	}
	vp8 := func(f frame) int {
		if f.key {
			return 10
		}
		return 3
	}
	audio := func(frame) int { return 1 }
	write("hen-video", "hen.1", "video", "video/VP8", "2026-11-22T16:00:00Z", ivf("a.ivf", 15), vp8)
	write("hen-audio", "hen.1", "audio", "audio/opus", "2026-11-22T16:00:00Z", ogg("a.ogg"), audio)
	write("sharon-video", "sharon.2", "video", "video/VP8", "2026-11-22T16:00:00.500Z", ivf("b.ivf", 15), vp8)
	write("sharon-audio", "sharon.2", "audio", "audio/opus", "2026-11-22T16:00:00.500Z", ogg("b.ogg"), audio)
	j, _ := json.MarshalIndent(map[string]any{"key": material, "tracks": tracks}, "", "  ")
	must(os.WriteFile("manifest.json", j, 0o644))
}
