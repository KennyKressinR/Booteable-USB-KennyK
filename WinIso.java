package app.isousb.booteable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Soporte para ISOs no híbridas (Windows y similares), estilo Rufus:
 * lee el UDF de la ISO, formatea el USB en FAT32 (MBR o GPT) y copia los archivos.
 * Si install.wim pasa de 4 GB lo divide en install.swm, install2.swm, ...
 * También contiene el formateador FAT32 que usa la pestaña «Formatear».
 */
final class WinIso {
    private WinIso() {
    }

    /** Tamaño máximo de un archivo en FAT32. */
    static long fatMaxFile = 0xFFFFFFFFL;
    /** Tamaño objetivo de cada parte SWM (3800 MiB, como DISM). */
    static long swmPart = 3800L * 1024 * 1024;

    private static final int CHUNK = 64 * 1024;
    private static final int DATE = 0x5821;   // 2024-01-01

    interface Ctl {
        boolean cancelled();

        void progress(long done, long total);
    }

    /** Fuente de bytes con acceso aleatorio. */
    interface Src {
        long size();

        void read(long off, byte[] b, int bo, int len) throws IOException;
    }

    static class Result {
        long files, bytes;
        int parts;
    }

    static class Info {
        boolean udf, efi, windows;
        long wim;
    }

    // ===================================================================
    //  Utilidades
    // ===================================================================

    static int u16(byte[] b, int o) {
        return (b[o] & 255) | ((b[o + 1] & 255) << 8);
    }

    static long u32(byte[] b, int o) {
        return (b[o] & 255L) | ((b[o + 1] & 255L) << 8) | ((b[o + 2] & 255L) << 16) | ((b[o + 3] & 255L) << 24);
    }

    static long u64(byte[] b, int o) {
        return u32(b, o) | (u32(b, o + 4) << 32);
    }

    static void rdAt(FileChannel ch, long pos, byte[] b, int off, int len) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(b, off, len);
        long p = pos;
        while (bb.hasRemaining()) {
            int n = ch.read(bb, p);
            if (n < 0) throw new IOException("Fin inesperado de la ISO");
            p += n;
        }
    }

    static String fmt(long b) {
        if (b >= (1L << 30)) return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
        if (b >= (1L << 20)) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return b + " B";
    }

    // ===================================================================
    //  Lector UDF (ECMA-167 / UDF 1.02, el formato de las ISOs de Windows)
    // ===================================================================

    static class UNode {
        String name;
        boolean dir;
        long size;
        byte[] emb;                                            // datos embebidos en el ICB
        final List<long[]> ext = new ArrayList<long[]>();      // {tipo, posición absoluta en la ISO, longitud}
    }

    static class Udf {
        final FileChannel ch;
        final long fsize;
        int bsz = 2048;
        long[] pstart = new long[0];
        final UNode root;

        Udf(FileChannel ch) throws IOException {
            this.ch = ch;
            this.fsize = ch.size();
            byte[] s = new byte[2048];
            long avdp = -1;
            long[] cand = {256L * 2048, fsize - 256L * 2048};
            for (long c : cand) {
                if (c < 0 || c + 2048 > fsize) continue;
                rdAt(ch, c, s, 0, 2048);
                if (u16(s, 0) == 2) {
                    avdp = c;
                    break;
                }
            }
            if (avdp < 0) throw new IOException("La imagen no tiene UDF");
            long vdsLen = u32(s, 16), vdsLoc = u32(s, 20);

            Map<Integer, Long> parts = new HashMap<Integer, Long>();
            int[] maps = null;
            long fsdLbn = 0;
            int fsdRef = 0;
            for (long i = 0; i < vdsLen / 2048 && i < 256; i++) {
                rdAt(ch, (vdsLoc + i) * 2048, s, 0, 2048);
                int id = u16(s, 0);
                if (id == 8) break;
                if (id == 5) {
                    parts.put(u16(s, 22), u32(s, 188));
                } else if (id == 6) {
                    bsz = (int) u32(s, 212);
                    fsdLbn = u32(s, 252);
                    fsdRef = u16(s, 256);
                    int nm = (int) u32(s, 268);
                    if (nm < 1 || nm > 8) throw new IOException("UDF inválido");
                    maps = new int[nm];
                    int p = 440;
                    for (int k = 0; k < nm; k++) {
                        int type = s[p] & 255, len = s[p + 1] & 255;
                        if (type != 1 || len < 6) {
                            throw new IOException("Este UDF usa un mapa de partición no soportado (UDF 2.50 o superior)");
                        }
                        maps[k] = u16(s, p + 4);
                        p += len;
                    }
                }
            }
            if (maps == null || parts.isEmpty()) throw new IOException("UDF incompleto");
            if (bsz != 2048) throw new IOException("Tamaño de bloque UDF no soportado: " + bsz);
            pstart = new long[maps.length];
            for (int k = 0; k < maps.length; k++) {
                Long st = parts.get(maps[k]);
                if (st == null) throw new IOException("Partición UDF no encontrada");
                pstart[k] = st;
            }
            if (fsdRef >= pstart.length) throw new IOException("UDF inválido (File Set)");
            rdAt(ch, (pstart[fsdRef] + fsdLbn) * bsz, s, 0, 2048);
            if (u16(s, 0) != 256) throw new IOException("No se encontró el File Set UDF");
            int rref = u16(s, 408);
            root = node("", u32(s, 404), rref);
            if (!root.dir) throw new IOException("Raíz UDF inválida");
        }

        UNode node(String name, long lbn, int pref) throws IOException {
            if (pref >= pstart.length) throw new IOException("Referencia de partición UDF inválida");
            byte[] b = new byte[bsz];
            rdAt(ch, (pstart[pref] + lbn) * bsz, b, 0, bsz);
            int id = u16(b, 0);
            if (id != 261 && id != 266) throw new IOException("Entrada UDF no soportada (tag " + id + ")");
            UNode n = new UNode();
            n.name = name;
            n.dir = (b[27] & 255) == 4;
            n.size = u64(b, 56);
            int adType = u16(b, 34) & 7;
            int lea, lad, base;
            if (id == 261) {
                lea = (int) u32(b, 168);
                lad = (int) u32(b, 172);
                base = 176;
            } else {
                lea = (int) u32(b, 208);
                lad = (int) u32(b, 212);
                base = 216;
            }
            int ad = base + lea;
            if (lea < 0 || lad < 0 || ad > bsz) throw new IOException("Entrada UDF dañada");
            if (adType == 3) {
                int l = (int) Math.min(Math.min((long) lad, n.size), bsz - ad);
                n.emb = Arrays.copyOfRange(b, ad, ad + Math.max(0, l));
                return n;
            }
            if (adType > 1) throw new IOException("Tipo de descriptor de asignación UDF no soportado");
            byte[] cur = b;
            int off = ad;
            int len = Math.min(lad, cur.length - off);
            int sz = adType == 0 ? 8 : 16;
            for (int guard = 0; guard < 4096; guard++) {
                int cnt = len / sz;
                long nextAbs = -1, nextLen = 0;
                for (int k = 0; k < cnt; k++) {
                    int o = off + k * sz;
                    long raw = u32(cur, o);
                    int type = (int) (raw >>> 30);
                    long l = raw & 0x3FFFFFFFL;
                    long lb = u32(cur, o + 4);
                    int pr = adType == 1 ? u16(cur, o + 8) : pref;
                    if (l == 0) break;
                    if (pr >= pstart.length) throw new IOException("Referencia de partición UDF inválida");
                    if (type == 3) {
                        nextAbs = (pstart[pr] + lb) * bsz;
                        nextLen = l;
                        break;
                    }
                    n.ext.add(new long[]{type, (pstart[pr] + lb) * bsz, l});
                }
                if (nextAbs < 0) break;
                byte[] nb = new byte[(int) Math.max(bsz, Math.min(nextLen, 1 << 20))];
                rdAt(ch, nextAbs, nb, 0, nb.length);
                if (u16(nb, 0) != 258) throw new IOException("Extensión UDF dañada");
                len = (int) Math.min(u32(nb, 20), nb.length - 24);
                cur = nb;
                off = 24;
            }
            return n;
        }

        Src source(final UNode n) {
            return new Src() {
                @Override
                public long size() {
                    return n.size;
                }

                @Override
                public void read(long off, byte[] b, int bo, int len) throws IOException {
                    if (n.emb != null) {
                        int avail = (int) Math.max(0, Math.min(len, n.emb.length - off));
                        if (avail > 0) System.arraycopy(n.emb, (int) off, b, bo, avail);
                        if (avail < len) Arrays.fill(b, bo + avail, bo + len, (byte) 0);
                        return;
                    }
                    long pos = 0;
                    int done = 0;
                    for (long[] e : n.ext) {
                        long el = e[2];
                        if (off + done < pos + el) {
                            long within = off + done - pos;
                            int take = (int) Math.min(len - done, el - within);
                            if (e[0] == 0) rdAt(ch, e[1] + within, b, bo + done, take);
                            else Arrays.fill(b, bo + done, bo + done + take, (byte) 0);
                            done += take;
                            if (done == len) return;
                        }
                        pos += el;
                    }
                    if (done < len) Arrays.fill(b, bo + done, bo + len, (byte) 0);
                }
            };
        }

        List<UNode> children(UNode d) throws IOException {
            if (d.size > (64L << 20)) throw new IOException("Directorio UDF demasiado grande");
            byte[] data = new byte[(int) d.size];
            source(d).read(0, data, 0, data.length);
            List<UNode> out = new ArrayList<UNode>();
            int p = 0;
            while (p + 38 <= data.length) {
                if (u16(data, p) != 257) break;
                int chars = data[p + 18] & 255;
                int lfi = data[p + 19] & 255;
                int liu = u16(data, p + 36);
                long lbn = u32(data, p + 24);
                int pref = u16(data, p + 28);
                int flen = (38 + liu + lfi + 3) & ~3;
                if ((chars & 0x0C) == 0 && lfi > 0 && p + 38 + liu + lfi <= data.length) {
                    out.add(node(decodeName(data, p + 38 + liu, lfi), lbn, pref));
                }
                p += flen;
            }
            return out;
        }

        static String decodeName(byte[] b, int off, int len) {
            int comp = b[off] & 255;
            StringBuilder sb = new StringBuilder();
            if (comp == 16) {
                for (int i = 1; i + 1 < len; i += 2) {
                    sb.append((char) (((b[off + i] & 255) << 8) | (b[off + i + 1] & 255)));
                }
            } else {
                for (int i = 1; i < len; i++) sb.append((char) (b[off + i] & 255));
            }
            return sb.toString();
        }
    }

    /** Detecta qué tipo de imagen es (sin escribir nada). */
    static Info analyze(FileChannel ch) {
        Info info = new Info();
        try {
            Udf u = new Udf(ch);
            info.udf = true;
            for (UNode c : u.children(u.root)) {
                String n = c.name.toLowerCase(Locale.ROOT);
                if (n.equals("bootmgr")) info.windows = true;
                if (n.equals("efi") && c.dir) {
                    for (UNode d : u.children(c)) {
                        if (!d.dir || !d.name.equalsIgnoreCase("boot")) continue;
                        for (UNode f : u.children(d)) {
                            String fn = f.name.toLowerCase(Locale.ROOT);
                            if (fn.equals("bootx64.efi") || fn.equals("bootia32.efi") || fn.equals("bootaa64.efi")) {
                                info.efi = true;
                            }
                        }
                    }
                }
                if (n.equals("sources") && c.dir) {
                    for (UNode f : u.children(c)) {
                        String fn = f.name.toLowerCase(Locale.ROOT);
                        if (fn.equals("boot.wim")) info.windows = true;
                        if (fn.equals("install.wim") || fn.equals("install.esd")) info.wim = f.size;
                    }
                }
            }
        } catch (IOException e) {
            // no es UDF o no se puede leer
        } catch (RuntimeException e) {
            // UDF dañado
        }
        return info;
    }

    // ===================================================================
    //  Árbol de archivos para FAT32
    // ===================================================================

    static class FNode {
        String name;
        boolean dir;
        long size;
        Src src;
        FNode parent;
        final List<FNode> kids = new ArrayList<FNode>();
        byte[] sfn;
        int nt;
        byte[][] lfn;
        long first, ncl;
    }

    static FNode buildTree(Udf u) throws IOException {
        return build(u, u.root, null, "");
    }

    private static FNode build(Udf u, UNode n, FNode parent, String name) throws IOException {
        FNode f = new FNode();
        f.name = name;
        f.dir = n.dir;
        f.parent = parent;
        if (n.dir) {
            for (UNode c : u.children(n)) f.kids.add(build(u, c, f, safeName(c.name)));
        } else {
            f.size = n.size;
            f.src = u.source(n);
        }
        return f;
    }

    static String safeName(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < 32 || "\"*/:<>?\\|".indexOf(c) >= 0 ? '_' : c);
        }
        int e = sb.length();
        while (e > 0 && (sb.charAt(e - 1) == '.' || sb.charAt(e - 1) == ' ')) e--;
        sb.setLength(e);
        if (sb.length() > 255) sb.setLength(255);
        return sb.length() == 0 ? "_" : sb.toString();
    }

    // ---- Nombres cortos (8.3) y largos (LFN) ----

    private static boolean validShort(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "!#$%&'()-@^_`{}~".indexOf(c) >= 0;
    }

    private static boolean hasLower(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= 'a' && s.charAt(i) <= 'z') return true;
        return false;
    }

    private static boolean hasUpper(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= 'A' && s.charAt(i) <= 'Z') return true;
        return false;
    }

    /** Devuelve {base, ext, flagsNT} si el nombre cabe en 8.3 conservando mayúsculas/minúsculas, o null. */
    private static String[] fitsShort(String name) {
        int dot = name.lastIndexOf('.');
        if (dot == 0) return null;
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot + 1) : "";
        if (base.length() == 0 || base.length() > 8 || ext.length() > 3 || base.indexOf('.') >= 0) return null;
        String all = base + ext;
        for (int i = 0; i < all.length(); i++) {
            char c = Character.toUpperCase(all.charAt(i));
            if (c > 127 || !validShort(c)) return null;
        }
        if ((hasLower(base) && hasUpper(base)) || (hasLower(ext) && hasUpper(ext))) return null;
        int nt = (hasLower(base) ? 0x08 : 0) | (hasLower(ext) ? 0x10 : 0);
        return new String[]{base.toUpperCase(Locale.ROOT), ext.toUpperCase(Locale.ROOT), String.valueOf(nt)};
    }

    private static byte[] pad11(String base, String ext) {
        byte[] r = new byte[11];
        Arrays.fill(r, (byte) ' ');
        byte[] bb = base.getBytes(StandardCharsets.ISO_8859_1), eb = ext.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(bb, 0, r, 0, Math.min(8, bb.length));
        System.arraycopy(eb, 0, r, 8, Math.min(3, eb.length));
        return r;
    }

    private static String cleanShort(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toUpperCase(s.charAt(i));
            if (c == ' ' || c == '.') continue;
            sb.append(c > 127 || !validShort(c) ? '_' : c);
        }
        return sb.toString();
    }

    private static int lfnChecksum(byte[] sfn) {
        int sum = 0;
        for (int i = 0; i < 11; i++) sum = ((((sum & 1) << 7) | ((sum & 0xFF) >> 1)) + (sfn[i] & 255)) & 0xFF;
        return sum;
    }

    private static byte[][] lfnEntries(String name, byte[] sfn) {
        int len = name.length();
        int n = (len + 12) / 13;
        int ck = lfnChecksum(sfn);
        int[] offs = {1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30};
        byte[][] out = new byte[n][];
        for (int i = 0; i < n; i++) {
            byte[] e = new byte[32];
            e[0] = (byte) ((i + 1) | (i == n - 1 ? 0x40 : 0));
            e[11] = 0x0F;
            e[13] = (byte) ck;
            for (int k = 0; k < 13; k++) {
                int idx = i * 13 + k;
                int ch = idx < len ? name.charAt(idx) : (idx == len ? 0 : 0xFFFF);
                e[offs[k]] = (byte) ch;
                e[offs[k] + 1] = (byte) (ch >> 8);
            }
            out[n - 1 - i] = e;           // en el directorio van de la última a la primera
        }
        return out;
    }

    /** Asigna nombre corto y, si hace falta, largo a cada hijo de un directorio. */
    static void names(FNode d) {
        Set<String> used = new HashSet<String>();
        List<FNode> pending = new ArrayList<FNode>();
        for (FNode k : d.kids) {
            String[] f = fitsShort(k.name);
            byte[] sfn = f == null ? null : pad11(f[0], f[1]);
            if (sfn != null && used.add(new String(sfn, StandardCharsets.ISO_8859_1))) {
                k.sfn = sfn;
                k.nt = Integer.parseInt(f[2]);
                k.lfn = null;
            } else {
                pending.add(k);
            }
        }
        for (FNode k : pending) {
            int dot = k.name.lastIndexOf('.');
            String base = cleanShort(dot > 0 ? k.name.substring(0, dot) : k.name);
            String ext = cleanShort(dot > 0 ? k.name.substring(dot + 1) : "");
            if (dot == 0) base = cleanShort(k.name);
            if (base.length() == 0) base = "_";
            if (ext.length() > 3) ext = ext.substring(0, 3);
            byte[] sfn = null;
            for (int n = 1; n < 1000000; n++) {
                String tail = "~" + n;
                String b = base.substring(0, Math.min(base.length(), 8 - tail.length())) + tail;
                byte[] c = pad11(b, ext);
                if (used.add(new String(c, StandardCharsets.ISO_8859_1))) {
                    sfn = c;
                    break;
                }
            }
            k.sfn = sfn;
            k.nt = 0;
            k.lfn = lfnEntries(k.name, sfn);
        }
    }

    // ===================================================================
    //  División de install.wim en install.swm, install2.swm, ...
    // ===================================================================

    private static class Seg {
        long len;
        byte[] mem;
        Src src;
        long soff;
    }

    private static class SegSrc implements Src {
        final Seg[] segs;
        final long[] starts;
        final long total;

        SegSrc(List<Seg> list) {
            List<Seg> m = new ArrayList<Seg>();
            for (Seg s : list) {
                if (s.len == 0) continue;
                Seg last = m.isEmpty() ? null : m.get(m.size() - 1);
                if (last != null && last.mem == null && s.mem == null && last.src == s.src && last.soff + last.len == s.soff) {
                    last.len += s.len;
                } else {
                    Seg c = new Seg();
                    c.len = s.len;
                    c.mem = s.mem;
                    c.src = s.src;
                    c.soff = s.soff;
                    m.add(c);
                }
            }
            segs = m.toArray(new Seg[0]);
            starts = new long[segs.length];
            long t = 0;
            for (int i = 0; i < segs.length; i++) {
                starts[i] = t;
                t += segs[i].len;
            }
            total = t;
        }

        @Override
        public long size() {
            return total;
        }

        @Override
        public void read(long off, byte[] b, int bo, int len) throws IOException {
            if (len == 0) return;
            int i = Arrays.binarySearch(starts, off);
            if (i < 0) i = -i - 2;
            int done = 0;
            while (done < len) {
                if (i >= segs.length) throw new IOException("Lectura fuera de rango");
                Seg s = segs[i];
                long within = off + done - starts[i];
                int take = (int) Math.min(len - done, s.len - within);
                if (s.mem != null) System.arraycopy(s.mem, (int) within, b, bo + done, take);
                else s.src.read(s.soff + within, b, bo + done, take);
                done += take;
                i++;
            }
        }
    }

    private static class Ent {
        long size, off, orig, ref;
        int flags;
        byte[] hash;
    }

    private static long[] reshdr(byte[] h, int o) {
        long sf = u64(h, o);
        return new long[]{sf & 0x00FFFFFFFFFFFFFFL, (sf >>> 56) & 255, u64(h, o + 8), u64(h, o + 16)};
    }

    private static void putRes(ByteBuffer b, int at, long size, int flags, long off, long orig) {
        b.putLong(at, size | ((long) flags << 56));
        b.putLong(at + 8, off);
        b.putLong(at + 16, orig);
    }

    /** Parte un WIM sin recomprimir: copia los recursos tal cual y reescribe cabecera y tabla de recursos. */
    static List<Src> splitWim(final Src w) throws IOException {
        byte[] h = new byte[208];
        w.read(0, h, 0, 208);
        if (!"MSWIM\0\0\0".equals(new String(h, 0, 8, StandardCharsets.ISO_8859_1))) {
            throw new IOException("install.wim no es un WIM válido");
        }
        if (u32(h, 8) != 208) throw new IOException("Cabecera WIM inesperada");
        long ver = u32(h, 12);
        if (ver != 0x10D00L) {
            throw new IOException("Este WIM no se puede dividir (versión 0x" + Long.toHexString(ver) + ", ¿ESD sólido?)");
        }
        if (u16(h, 40) != 1 || u16(h, 42) != 1) throw new IOException("El WIM ya está dividido");
        long[] lt = reshdr(h, 48), xml = reshdr(h, 72), bm = reshdr(h, 96);
        if ((lt[1] & 0x04) != 0) throw new IOException("Tabla de recursos comprimida: no soportado");
        int cnt = (int) (lt[0] / 50);
        byte[] tb = new byte[cnt * 50];
        w.read(lt[2], tb, 0, tb.length);
        byte[] xb = new byte[(int) xml[0]];
        if (xb.length > 0) w.read(xml[2], xb, 0, xb.length);

        List<Ent> meta = new ArrayList<Ent>(), files = new ArrayList<Ent>();
        for (int i = 0; i < cnt; i++) {
            int o = i * 50;
            Ent e = new Ent();
            long sf = u64(tb, o);
            e.size = sf & 0x00FFFFFFFFFFFFFFL;
            e.flags = (int) ((sf >>> 56) & 255);
            e.off = u64(tb, o + 8);
            e.orig = u64(tb, o + 16);
            e.ref = u32(tb, o + 26);
            e.hash = Arrays.copyOfRange(tb, o + 30, o + 50);
            if ((e.flags & 0x01) != 0) continue;                       // entrada libre
            if ((e.flags & 0x10) != 0) throw new IOException("WIM sólido: no se puede dividir");
            if ((e.flags & 0x02) != 0) meta.add(e);
            else files.add(e);
        }
        Collections.sort(files, new Comparator<Ent>() {
            @Override
            public int compare(Ent a, Ent b) {
                return a.off < b.off ? -1 : (a.off == b.off ? 0 : 1);
            }
        });

        final long overhead = 208 + xb.length;
        List<List<Ent>> parts = new ArrayList<List<Ent>>();
        List<Ent> cur = new ArrayList<Ent>();
        long used = overhead;
        for (Ent e : meta) {
            cur.add(e);
            used += e.size + 50;
        }
        for (Ent e : files) {
            long add = e.size + 50;
            if (used + add > swmPart && !cur.isEmpty()) {
                parts.add(cur);
                cur = new ArrayList<Ent>();
                used = overhead;
            }
            if (used + add > fatMaxFile) throw new IOException("Un recurso del WIM es demasiado grande para FAT32");
            cur.add(e);
            used += add;
        }
        parts.add(cur);

        List<Src> out = new ArrayList<Src>();
        int total = parts.size();
        for (int p = 0; p < total; p++) {
            List<Ent> es = parts.get(p);
            long[] noff = new long[es.size()];
            long pos = 208;
            for (int i = 0; i < es.size(); i++) {
                noff[i] = pos;
                pos += es.get(i).size;
            }
            long ltOff = pos, ltSize = es.size() * 50L, xmlOff = ltOff + ltSize;

            byte[] nt = new byte[(int) ltSize];
            ByteBuffer t = ByteBuffer.wrap(nt).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < es.size(); i++) {
                Ent e = es.get(i);
                int o = i * 50;
                putRes(t, o, e.size, e.flags, noff[i], e.orig);
                t.putShort(o + 24, (short) (p + 1));
                t.putInt(o + 26, (int) e.ref);
                System.arraycopy(e.hash, 0, nt, o + 30, 20);
            }

            byte[] nh = h.clone();
            ByteBuffer hb = ByteBuffer.wrap(nh).order(ByteOrder.LITTLE_ENDIAN);
            long fl = u32(h, 16);
            fl &= ~0x40L;
            fl |= 0x08L;                                  // SPANNED
            if (p > 0) fl |= 0x10L;                       // RESOURCE_ONLY: sin metadatos
            hb.putInt(16, (int) fl);
            hb.putShort(40, (short) (p + 1));
            hb.putShort(42, (short) total);
            putRes(hb, 48, ltSize, 0, ltOff, ltSize);
            putRes(hb, 72, xb.length, 0, xmlOff, xb.length);
            Arrays.fill(nh, 96, 120, (byte) 0);
            Arrays.fill(nh, 124, 148, (byte) 0);
            if (p == 0 && bm[0] != 0) {
                for (int i = 0; i < es.size(); i++) {
                    Ent e = es.get(i);
                    if ((e.flags & 0x02) != 0 && e.off == bm[2]) putRes(hb, 96, e.size, e.flags, noff[i], e.orig);
                }
            }
            if (p > 0) hb.putInt(120, 0);

            List<Seg> segs = new ArrayList<Seg>();
            Seg sh = new Seg();
            sh.len = 208;
            sh.mem = nh;
            segs.add(sh);
            for (Ent e : es) {
                Seg s = new Seg();
                s.len = e.size;
                s.src = w;
                s.soff = e.off;
                segs.add(s);
            }
            Seg sl = new Seg();
            sl.len = nt.length;
            sl.mem = nt;
            segs.add(sl);
            Seg sx = new Seg();
            sx.len = xb.length;
            sx.mem = xb;
            segs.add(sx);
            out.add(new SegSrc(segs));
        }
        return out;
    }

    private static void walkFiles(FNode d, List<FNode> out) {
        for (FNode k : d.kids) {
            if (k.dir) walkFiles(k, out);
            else out.add(k);
        }
    }

    /** Reemplaza cada .wim de más de 4 GB por sus partes .swm; falla si hay otro archivo que no cabe en FAT32. */
    private static int splitBigFiles(FNode root) throws IOException {
        List<FNode> files = new ArrayList<FNode>();
        walkFiles(root, files);
        int extra = 0;
        for (FNode f : files) {
            if (f.size <= fatMaxFile) continue;
            if (!f.name.toLowerCase(Locale.ROOT).endsWith(".wim")) {
                throw new IOException("«" + f.name + "» pesa más de 4 GB y FAT32 no lo admite");
            }
            List<Src> parts = splitWim(f.src);
            String base = f.name.substring(0, f.name.length() - 4);
            FNode dir = f.parent;
            int at = dir.kids.indexOf(f);
            dir.kids.remove(at);
            for (int i = 0; i < parts.size(); i++) {
                FNode n = new FNode();
                n.name = base + (i == 0 ? "" : String.valueOf(i + 1)) + ".swm";
                n.parent = dir;
                n.src = parts.get(i);
                n.size = n.src.size();
                if (n.size > fatMaxFile) throw new IOException("Una parte del WIM pasa de 4 GB");
                dir.kids.add(at + i, n);
            }
            extra += parts.size();
        }
        return extra;
    }

    // ===================================================================
    //  Formato FAT32 (MBR o GPT) — lo usan «Formatear» y «Extraer archivos»
    // ===================================================================

    static void zero(IsoUsbPlugin.Msc m, long lba, long count, Ctl ctl) throws IOException {
        int per = Math.max(1, CHUNK / m.bs);
        byte[] z = new byte[per * m.bs];
        while (count > 0) {
            if (ctl.cancelled()) throw new IOException("Cancelado por el usuario");
            int n = (int) Math.min(per, count);
            m.write(lba, z, 0, n);
            lba += n;
            count -= n;
        }
    }

    private static byte[] gptHeader(int bs, long cur, long bak, long first, long last, byte[] guid, long entLba, int entCrc) {
        byte[] h = new byte[bs];
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        System.arraycopy("EFI PART".getBytes(StandardCharsets.US_ASCII), 0, h, 0, 8);
        b.putInt(8, 0x00010000);
        b.putInt(12, 92);
        b.putLong(24, cur);
        b.putLong(32, bak);
        b.putLong(40, first);
        b.putLong(48, last);
        System.arraycopy(guid, 0, h, 56, 16);
        b.putLong(72, entLba);
        b.putInt(80, 128);
        b.putInt(84, 128);
        b.putInt(88, entCrc);
        CRC32 crc = new CRC32();
        crc.update(h, 0, 92);
        b.putInt(16, (int) crc.getValue());
        return h;
    }

    private static byte[] fsInfo(int bs, long free, long next) {
        byte[] fsi = new byte[bs];
        ByteBuffer fb = ByteBuffer.wrap(fsi).order(ByteOrder.LITTLE_ENDIAN);
        fb.putInt(0, 0x41615252);
        fb.putInt(484, 0x61417272);
        fb.putInt(488, (int) free);
        fb.putInt(492, (int) next);
        fsi[510] = 0x55;
        fsi[511] = (byte) 0xAA;
        return fsi;
    }

    static void formatFat32(IsoUsbPlugin.Msc m, boolean gpt, String label, Ctl ctl) throws IOException {
        final int bs = m.bs;
        final long total = m.blocks;
        final FatInfo lay = layoutFor(bs, total, gpt);
        final long start = lay.start, end = lay.end, sectors = lay.sectors, fat = lay.fatSz, clusters = lay.clusters;
        final int egs = lay.egs, spc = lay.spc, reserved = lay.reserved;

        Random rnd = new Random();

        // 1) Limpiar el primer y el último MiB (firmas viejas de ISO, GPT, etc.)
        zero(m, 0, start, ctl);
        zero(m, total - start, start, ctl);

        // 2) Tabla de particiones
        byte[] mbr = new byte[bs];
        ByteBuffer mb = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN);
        mb.putInt(440, rnd.nextInt());
        if (!gpt) {
            mbr[446] = 0x00;
            mbr[447] = (byte) 0xFE;
            mbr[448] = (byte) 0xFF;
            mbr[449] = (byte) 0xFF;
            mbr[450] = 0x0C;                                   // FAT32 LBA
            mbr[451] = (byte) 0xFE;
            mbr[452] = (byte) 0xFF;
            mbr[453] = (byte) 0xFF;
            mb.putInt(454, (int) start);
            mb.putInt(458, (int) sectors);
        } else {                                               // MBR protector
            mbr[447] = 0x00;
            mbr[448] = 0x02;
            mbr[449] = 0x00;
            mbr[450] = (byte) 0xEE;
            mbr[451] = (byte) 0xFF;
            mbr[452] = (byte) 0xFF;
            mbr[453] = (byte) 0xFF;
            mb.putInt(454, 1);
            mb.putInt(458, (int) Math.min(total - 1, 0xFFFFFFFFL));
        }
        mbr[510] = 0x55;
        mbr[511] = (byte) 0xAA;
        m.write(0, mbr, 0, 1);

        if (gpt) {
            byte[] ent = new byte[egs * bs];
            ByteBuffer eb = ByteBuffer.wrap(ent).order(ByteOrder.LITTLE_ENDIAN);
            byte[] type = {(byte) 0xA2, (byte) 0xA0, (byte) 0xD0, (byte) 0xEB, (byte) 0xE5, (byte) 0xB9, 0x33, 0x44,
                    (byte) 0x87, (byte) 0xC0, 0x68, (byte) 0xB6, (byte) 0xB7, 0x26, (byte) 0x99, (byte) 0xC7};
            System.arraycopy(type, 0, ent, 0, 16);                // Microsoft basic data
            byte[] pg = new byte[16];
            rnd.nextBytes(pg);
            System.arraycopy(pg, 0, ent, 16, 16);
            eb.putLong(32, start);
            eb.putLong(40, end);
            byte[] nm = "ISOUSB".getBytes(StandardCharsets.UTF_16LE);
            System.arraycopy(nm, 0, ent, 56, nm.length);
            CRC32 crc = new CRC32();
            crc.update(ent, 0, 128 * 128);
            int entCrc = (int) crc.getValue();
            byte[] dg = new byte[16];
            rnd.nextBytes(dg);
            long first = 2 + egs, last = total - egs - 2;
            m.write(1, gptHeader(bs, 1, total - 1, first, last, dg, 2, entCrc), 0, 1);
            m.write(2, ent, 0, egs);
            m.write(total - 1 - egs, ent, 0, egs);
            m.write(total - 1, gptHeader(bs, total - 1, 1, first, last, dg, total - 1 - egs, entCrc), 0, 1);
        }

        // 3) FAT32: reservados + FAT1 + FAT2 + cluster raíz en cero
        zero(m, start, reserved + 2 * fat + spc, ctl);
        byte[] boot = new byte[bs];
        ByteBuffer bb = ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN);
        boot[0] = (byte) 0xEB;
        boot[1] = 0x58;
        boot[2] = (byte) 0x90;
        System.arraycopy("MSWIN4.1".getBytes(StandardCharsets.US_ASCII), 0, boot, 3, 8);
        bb.putShort(11, (short) bs);
        boot[13] = (byte) spc;
        bb.putShort(14, (short) reserved);
        boot[16] = 2;
        boot[21] = (byte) 0xF8;
        bb.putShort(24, (short) 63);
        bb.putShort(26, (short) 255);
        bb.putInt(28, (int) start);
        bb.putInt(32, (int) sectors);
        bb.putInt(36, (int) fat);
        bb.putInt(44, 2);
        bb.putShort(48, (short) 1);
        bb.putShort(50, (short) 6);
        boot[64] = (byte) 0x80;
        boot[66] = 0x29;
        bb.putInt(67, rnd.nextInt());
        System.arraycopy(String.format(Locale.US, "%-11s", label).getBytes(StandardCharsets.US_ASCII), 0, boot, 71, 11);
        System.arraycopy("FAT32   ".getBytes(StandardCharsets.US_ASCII), 0, boot, 82, 8);
        boot[510] = 0x55;
        boot[511] = (byte) 0xAA;

        byte[] fsi = fsInfo(bs, clusters - 1, 3);

        byte[] fe = new byte[bs];
        ByteBuffer feb = ByteBuffer.wrap(fe).order(ByteOrder.LITTLE_ENDIAN);
        feb.putInt(0, 0x0FFFFFF8);
        feb.putInt(4, 0x0FFFFFFF);
        feb.putInt(8, 0x0FFFFFFF);

        m.write(start, boot, 0, 1);
        m.write(start + 1, fsi, 0, 1);
        m.write(start + 6, boot, 0, 1);
        m.write(start + 7, fsi, 0, 1);
        m.write(start + reserved, fe, 0, 1);
        m.write(start + reserved + fat, fe, 0, 1);
        m.sync();
    }

    // ===================================================================
    //  Escritura del árbol de archivos en el FAT32 recién formateado
    // ===================================================================

    private static class FatInfo {
        int bs, spc, reserved, egs;
        long start, fatSz, clusters, dataLba, sectors, end;
    }

    /** Calcula el diseño FAT32 para un disco de 'total' sectores (es lo que escribe formatFat32). */
    private static FatInfo layoutFor(int bs, long total, boolean gpt) throws IOException {
        final long start = Math.max(1, 1048576L / bs);       // partición alineada a 1 MiB
        final int egs = Math.max(1, 16384 / bs);              // sectores de la tabla GPT (128 x 128 B)
        final long end = gpt ? total - egs - 2 : total - 1;   // último LBA utilizable
        long sectors = end - start + 1;
        if (sectors < 70000) throw new IOException("El USB es demasiado pequeño para FAT32");
        if (sectors > 0xFFFFFFFFL) sectors = 0xFFFFFFFFL;

        long bytes = sectors * bs;
        int cb = bytes <= (8L << 30) ? 4096 : bytes <= (16L << 30) ? 8192 : bytes <= (32L << 30) ? 16384 : 32768;
        int spc = Math.max(1, cb / bs);
        while (spc > 1 && (sectors - 32) / spc < 65525) spc /= 2;
        final int reserved = 32;
        long fat = 1, clusters = 0;
        for (int i = 0; i < 64; i++) {
            clusters = (sectors - reserved - 2 * fat) / spc;
            long need = ((clusters + 2) * 4 + bs - 1) / bs;
            if (need <= fat) break;
            fat = need;
        }
        if (clusters < 65525) throw new IOException("El USB es demasiado pequeño para FAT32");
        FatInfo f = new FatInfo();
        f.bs = bs;
        f.start = start;
        f.egs = egs;
        f.end = end;
        f.sectors = sectors;
        f.spc = spc;
        f.reserved = reserved;
        f.fatSz = fat;
        f.clusters = clusters;
        f.dataLba = start + reserved + 2 * fat;
        return f;
    }

    private static FatInfo readFat(IsoUsbPlugin.Msc m) throws IOException {
        FatInfo f = new FatInfo();
        f.bs = m.bs;
        f.start = Math.max(1, 1048576L / m.bs);
        byte[] s = new byte[m.bs];
        m.read(f.start, s, 0, 1);
        if (u16(s, 510) != 0xAA55 || u16(s, 11) != m.bs) throw new IOException("El USB no tiene el FAT32 esperado");
        f.spc = s[13] & 255;
        f.reserved = u16(s, 14);
        f.fatSz = u32(s, 36);
        f.sectors = u32(s, 32);
        f.dataLba = f.start + f.reserved + 2 * f.fatSz;
        f.clusters = (f.sectors - f.reserved - 2 * f.fatSz) / f.spc;
        return f;
    }

    private static long dirBytes(FNode d) {
        long n = d.parent == null ? 0 : 2;
        for (FNode k : d.kids) n += (k.lfn == null ? 0 : k.lfn.length) + 1;
        return Math.max(1, n) * 32;
    }

    private static void le16(byte[] o, int p, int v) {
        o[p] = (byte) v;
        o[p + 1] = (byte) (v >> 8);
    }

    private static void le32(byte[] o, int p, long v) {
        o[p] = (byte) v;
        o[p + 1] = (byte) (v >> 8);
        o[p + 2] = (byte) (v >> 16);
        o[p + 3] = (byte) (v >> 24);
    }

    private static int putEntry(byte[] o, int p, byte[] name11, int attr, long cl, long size, int nt) {
        System.arraycopy(name11, 0, o, p, 11);
        o[p + 11] = (byte) attr;
        o[p + 12] = (byte) nt;
        le16(o, p + 16, DATE);
        le16(o, p + 18, DATE);
        le16(o, p + 20, (int) (cl >> 16));
        le16(o, p + 24, DATE);
        le16(o, p + 26, (int) cl);
        le32(o, p + 28, size);
        return p + 32;
    }

    private static byte[] dirImage(FNode d, int cb) {
        byte[] out = new byte[(int) (d.ncl * cb)];
        int p = 0;
        if (d.parent != null) {
            byte[] dot = new byte[11];
            Arrays.fill(dot, (byte) ' ');
            dot[0] = '.';
            byte[] dd = dot.clone();
            dd[1] = '.';
            p = putEntry(out, p, dot, 0x10, d.first, 0, 0);
            p = putEntry(out, p, dd, 0x10, d.parent.parent == null ? 0 : d.parent.first, 0, 0);
        }
        for (FNode k : d.kids) {
            if (k.lfn != null) {
                for (byte[] e : k.lfn) {
                    System.arraycopy(e, 0, out, p, 32);
                    p += 32;
                }
            }
            p = putEntry(out, p, k.sfn, k.dir ? 0x10 : 0x20, k.first, k.dir ? 0 : k.size, k.nt);
        }
        return out;
    }

    private static void chain(int[] fat, long first, long n) {
        for (long i = 0; i < n; i++) fat[(int) (first + i)] = i == n - 1 ? 0x0FFFFFFF : (int) (first + i + 1);
    }

    private static void writeBytes(IsoUsbPlugin.Msc m, long lba, byte[] data, Ctl ctl) throws IOException {
        int per = Math.max(1, CHUNK / m.bs);
        int sectors = data.length / m.bs;
        int off = 0;
        while (sectors > 0) {
            if (ctl.cancelled()) throw new IOException("Cancelado por el usuario");
            int n = Math.min(per, sectors);
            m.write(lba, data, off, n);
            lba += n;
            off += n * m.bs;
            sectors -= n;
        }
    }

    /** Detecta qué se va a hacer sin tocar el USB: lista los archivos y avisa si algo no cabe. */
    static Result extract(IsoUsbPlugin.Msc m, FNode root, boolean gpt, String label, Ctl ctl) throws IOException {
        Result r = new Result();
        r.parts = splitBigFiles(root);                           // falla antes de borrar nada
        List<FNode> files = new ArrayList<FNode>();
        walkFiles(root, files);
        long total = 0;
        for (FNode f : files) total += f.size;
        FatInfo fi = layoutFor(m.bs, m.blocks, gpt);
        final int cb = fi.spc * fi.bs;

        // Asignar clusters: directorios primero (en anchura), luego archivos
        long next = 2;
        List<FNode> dirs = new ArrayList<FNode>();
        ArrayDeque<FNode> q = new ArrayDeque<FNode>();
        q.add(root);
        List<FNode> order = new ArrayList<FNode>();
        while (!q.isEmpty()) {
            FNode d = q.poll();
            dirs.add(d);
            names(d);
            d.ncl = (dirBytes(d) + cb - 1) / cb;
            d.first = next;
            next += d.ncl;
            for (FNode k : d.kids) {
                if (k.dir) q.add(k);
                else order.add(k);
            }
        }
        for (FNode f : order) {
            if (f.size <= 0) continue;
            f.ncl = (f.size + cb - 1) / cb;
            f.first = next;
            next += f.ncl;
        }
        long used = next - 2;
        if (used > fi.clusters) {
            throw new IOException("Los archivos (" + fmt(total) + ") no caben en este USB: con clusters de " + cb
                    + " B hacen falta " + fmt(used * cb) + " y hay " + fmt(fi.clusters * cb));
        }

        // Desde aquí se borra el USB
        formatFat32(m, gpt, label, ctl);
        FatInfo real = readFat(m);
        if (real.spc != fi.spc || real.fatSz != fi.fatSz || real.clusters != fi.clusters) {
            throw new IOException("El FAT32 creado no coincide con lo planeado");
        }

        int[] fat = new int[(int) (fi.fatSz * fi.bs / 4)];
        fat[0] = 0x0FFFFFF8;
        fat[1] = 0x0FFFFFFF;
        for (FNode d : dirs) chain(fat, d.first, d.ncl);
        for (FNode f : order) if (f.size > 0) chain(fat, f.first, f.ncl);

        for (FNode d : dirs) writeBytes(m, fi.dataLba + (d.first - 2) * fi.spc, dirImage(d, cb), ctl);

        int chunk = Math.max(m.bs, (CHUNK / m.bs) * m.bs);
        byte[] buf = new byte[chunk];
        long done = 0;
        for (FNode f : order) {
            if (f.size <= 0) continue;
            long lba = fi.dataLba + (f.first - 2) * fi.spc;
            long off = 0;
            while (off < f.size) {
                if (ctl.cancelled()) throw new IOException("Cancelado por el usuario");
                int want = (int) Math.min(chunk, f.size - off);
                f.src.read(off, buf, 0, want);
                int pad = ((want + m.bs - 1) / m.bs) * m.bs;
                if (pad > want) Arrays.fill(buf, want, pad, (byte) 0);
                m.write(lba, buf, 0, pad / m.bs);
                lba += pad / m.bs;
                off += want;
                done += want;
                ctl.progress(done, total);
            }
        }

        // FAT (las dos copias) y FSInfo
        int ints = chunk / 4;
        ByteBuffer fb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        for (int copy = 0; copy < 2; copy++) {
            long lba = fi.start + fi.reserved + copy * fi.fatSz;
            for (long i = 0; i < fat.length; i += ints) {
                if (ctl.cancelled()) throw new IOException("Cancelado por el usuario");
                int n = (int) Math.min(ints, fat.length - i);
                fb.clear();
                for (int k = 0; k < ints; k++) fb.putInt(k < n ? fat[(int) (i + k)] : 0);
                int sectors = (n * 4 + fi.bs - 1) / fi.bs;
                m.write(lba, buf, 0, sectors);
                lba += sectors;
            }
        }
        byte[] fsi = fsInfo(fi.bs, fi.clusters - used, next);
        m.write(fi.start + 1, fsi, 0, 1);
        m.write(fi.start + 7, fsi, 0, 1);
        m.sync();
        ctl.progress(total, total);

        r.files = files.size();
        r.bytes = total;
        return r;
    }
}
