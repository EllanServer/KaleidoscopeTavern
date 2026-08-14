import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
public class PngCompare {
  public static void main(String[] a) throws Exception {
    Path head = Paths.get(a[0]); Path work = Paths.get(a[1]);
    Files.walk(head).filter(p -> p.toString().endsWith(".png")).forEach(p -> {
      try {
        String rel = head.relativize(p).toString().replace("\\", "/");
        Path w = work.resolve(rel);
        BufferedImage h = ImageIO.read(p.toFile()); BufferedImage ww = ImageIO.read(w.toFile());
        String hh = hash(h), wh = hash(ww);
        System.out.println(rel + " : " + h.getWidth() + "x" + h.getHeight() + " RGBA一致=" + hh.equals(wh));
      } catch (Exception e) { System.out.println(p + " : ERROR " + e); }
    });
  }
  static String hash(BufferedImage im) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    md.update((im.getWidth() + ":" + im.getHeight()).getBytes());
    int[] row = new int[im.getWidth()];
    for (int y = 0; y < im.getHeight(); y++) {
      im.getRGB(0, y, im.getWidth(), 1, row, 0, im.getWidth());
      for (int v : row) { md.update((byte)(v >>> 24)); md.update((byte)(v >>> 16)); md.update((byte)(v >>> 8)); md.update((byte)v); }
    }
    StringBuilder sb = new StringBuilder(); for (byte b : md.digest()) sb.append(String.format("%02x", b));
    return sb.toString();
  }
}