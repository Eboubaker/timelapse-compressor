import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Main {
    @Contract("_ -> new")
    public static @NotNull Dimension probeDimension(File resourceFile) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(resourceFile)) {
            final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(in);
                    return new Dimension(reader.getWidth(0), reader.getHeight(0));
                } finally {
                    reader.dispose();
                }
            }
        }
        throw new IOException("Failed to probe image dimensions: " + resourceFile);
    }

    public static void main(String[] args) throws IOException {
        var timelapseImagesDirPath = Path.of("E:\\games\\Factorio.v1.1.92\\script-output\\screenshots\\1281876515\\auto_singleTick_nauvis");
        var compressedOutputPath = Path.of("compressed");
        var decompressedOutputPath = Path.of("decompressed");

        compressedOutputPath.toFile().mkdir();
        compressedOutputPath.toFile().mkdir();
        compress(timelapseImagesDirPath, compressedOutputPath);
        decompress(compressedOutputPath, decompressedOutputPath);
        compare(timelapseImagesDirPath, decompressedOutputPath);
    }

    static void compress(Path imagesDirPath, Path compressedOutputPath) throws IOException {
        var imagesFiles = sortedPNGImageList(imagesDirPath);
        var dim = probeDimension(imagesFiles.get(0));
        int width = dim.width;
        int height = dim.height;
        int nthreads = Math.min(Runtime.getRuntime().availableProcessors(), imagesFiles.size());
        int chunk = imagesFiles.size() / nthreads;
        if(chunk < 1) {
            nthreads = 1;
            chunk = imagesFiles.size();
        }
        var executorService = Executors.newFixedThreadPool(nthreads);
        var results = new ArrayList<Future<?>>();
        System.out.printf("found %d images, starting compression...\n", imagesFiles.size());
        var firstImageFile = imagesFiles.get(0);
        Files.copy(firstImageFile.toPath(),
                compressedOutputPath.resolve(firstImageFile.getName()), StandardCopyOption.REPLACE_EXISTING);
        for (int t = 0; t < nthreads; t++) {
            int finalT = t;
            int finalChunk = chunk;
            int finalNthreads = nthreads;
            results.add(executorService.submit(() -> {
                try {
                    int start = finalT * finalChunk;
                    int end = start + finalChunk - 1;
                    if (finalT == finalNthreads - 1) {
                        end = imagesFiles.size() - 2;
                    }
                    System.out.printf("Thread: %d will work on range [%d,%d]\n",
                            finalT, start, end);
                    for (var i = start; i <= end; i++) {
                        var imf1 = imagesFiles.get(i);
                        var imf2 = imagesFiles.get(i + 1);
                        System.out.printf("thread %d image pair: %d of %d diffing images: %s <==> %s\n",
                                finalT, i + 1, imagesFiles.size() - 1, imf1.getName(), imf2.getName());
                        var image1 = ImageIO.read(imf1);
                        var image2 = ImageIO.read(imf2);
                        var diffImg = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                        var gfx = diffImg.getGraphics();
                        gfx.setColor(new Color(0, 0, 0, 0));
                        gfx.fillRect(0, 0, width, height);
                        for (int y = 0; y < height; y++) {
                            for (int x = 0; x < width; x++) {
                                int px1 = image1.getRGB(x, y);
                                int px2 = image2.getRGB(x, y);
                                int diff = px1 - px2;
                                if (diff != 0) {
                                    diffImg.setRGB(x, y, diff);
                                }
                            }
                        }
                        ImageIO.write(diffImg, "png",
                                compressedOutputPath.resolve(imf2.getName()).toFile());
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        results.forEach(integerFuture -> {
            try {
                integerFuture.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        executorService.shutdown();
    }

    static List<File> sortedPNGImageList(Path dir) {
        var numberPattern = Pattern.compile(".*?(\\d+).*");
        return Arrays.stream(Objects.requireNonNull(dir.toFile().listFiles((_d, name) -> name.endsWith(".png"))))
                .sorted((p1, p2) -> {
                    var fn1 = p1.getName();
                    var fn2 = p2.getName();
                    var m1 = numberPattern.matcher(fn1);
                    var m2 = numberPattern.matcher(fn2);
                    if (!m1.matches() || !m2.matches()) {
                        throw new RuntimeException("no number match");
                    }
                    var n1 = Integer.parseInt(m1.group(1));
                    var n2 = Integer.parseInt(m2.group(1));
                    return n1 - n2;
                })
                .collect(Collectors.toList());
    }

    static void decompress(Path compressedImagesDirPath, Path decompressedOutputPath) throws IOException {
        var imagesFiles = sortedPNGImageList(compressedImagesDirPath);
        var dim = probeDimension(imagesFiles.get(0));
        int width = dim.width;
        int height = dim.height;
        System.out.printf("found %d images, starting decompression...\n", imagesFiles.size());
        var firstImgFile = imagesFiles.remove(0);
        Files.copy(firstImgFile.toPath(),
                decompressedOutputPath.resolve(firstImgFile.getName()), StandardCopyOption.REPLACE_EXISTING);
        var currentImage = ImageIO.read(firstImgFile);
        for (var i = 0; i < imagesFiles.size(); i++) {
            var subtractionResultImageFile = imagesFiles.get(i);
            System.out.printf("image %d of %d: %s\n",
                    i + 2, imagesFiles.size() + 1, subtractionResultImageFile.getName());
            var subtractionResultImage = ImageIO.read(subtractionResultImageFile);
            var resultIMG = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int px1 = currentImage.getRGB(x, y);
                    int diffValue = subtractionResultImage.getRGB(x, y);
                    resultIMG.setRGB(x, y, px1 - diffValue);
                }
            }
            ImageIO.write(resultIMG, "png",
                    decompressedOutputPath.resolve(subtractionResultImageFile.getName()).toFile());
            currentImage = resultIMG;
        }
    }

    static void compare(Path imageDirPath1, Path imageDirPath2) throws IOException {
        var imagesFiles1 = sortedPNGImageList(imageDirPath1);
        var imagesFiles2 = sortedPNGImageList(imageDirPath2);
        System.out.print("running comparison of decompression vs original...\n");
        var dim = probeDimension(imagesFiles1.get(0));
        int width = dim.width;
        int height = dim.height;
        for (var i = 0; i < imagesFiles1.size(); i++) {
            System.out.printf("image: %d of %d: %s\n", i + 1,
                    imagesFiles1.size(), imagesFiles2.get(i).getName());
            var imf1 = imagesFiles1.get(i);
            var imf2 = imagesFiles2.get(i);
            var image1 = ImageIO.read(imf1);
            var image2 = ImageIO.read(imf2);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int px1 = image1.getRGB(x, y);
                    int px2 = image2.getRGB(x, y);
                    if (px1 != px2) {
                        System.out.printf("Warning invalid diff at y=%d x=%x " +
                                        "in images %s <==> %s pixel diff: %s <==> %s\n",
                                y, x, imf1.getAbsolutePath(), imf2.getAbsolutePath(),
                                Integer.toHexString(px1), Integer.toHexString(px2));
                    }
                }
            }
        }
    }
}
