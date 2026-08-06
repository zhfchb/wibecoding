package com.example.agent.rag;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;

/**
 * PDF 文本抽取器：基于 Apache PDFBox 3.x。
 *
 * <p>用法：{@code String text = PdfTextExtractor.extract(inputStream);}
 * 抽取失败时抛出 {@link IllegalArgumentException}，由上层统一处理为 400 错误。
 */
@Slf4j
public final class PdfTextExtractor {

    private PdfTextExtractor() {
    }

    /**
     * 从 PDF 输入流中抽取纯文本。
     *
     * @param inputStream PDF 文件输入流（调用方负责关闭原始 MultipartFile）
     * @return 抽取出的文本；扫描版 PDF（无文字层）返回空字符串
     */
    public static String extract(InputStream inputStream) {
        try (PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            // 按阅读顺序输出
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            log.debug("PDF 抽取完成: pages={}, textLength={}",
                    document.getNumberOfPages(), text.length());
            return text;
        } catch (IOException e) {
            log.error("PDF 解析失败", e);
            throw new IllegalArgumentException("PDF 解析失败，请确认文件未损坏", e);
        }
    }
}
