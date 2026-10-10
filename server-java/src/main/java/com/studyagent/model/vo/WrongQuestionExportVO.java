package com.studyagent.model.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.alibaba.excel.annotation.write.style.ContentRowHeight;
import com.alibaba.excel.annotation.write.style.HeadRowHeight;
import lombok.Data;

/**
 * 错题导出 Excel 的行模型。
 *
 * <p>必须用一个独立的类而不是直接导出 {@link WrongQuestionVO}：
 * EasyExcel 是按 {@code @ExcelProperty} 注解写列的，直接导出 VO 会把
 * 内部字段名当表头、还得额外处理标签列表的序列化。</p>
 */
@Data
@HeadRowHeight(22)
@ContentRowHeight(40)
public class WrongQuestionExportVO {

    @ExcelProperty("序号")
    @ColumnWidth(6)
    private Integer index;

    @ExcelProperty("科目")
    @ColumnWidth(10)
    private String subject;

    @ExcelProperty("题型")
    @ColumnWidth(10)
    private String questionType;

    @ExcelProperty("题干")
    @ColumnWidth(60)
    private String content;

    @ExcelProperty("正确答案")
    @ColumnWidth(40)
    private String answer;

    @ExcelProperty("解析")
    @ColumnWidth(50)
    private String analysis;

    @ExcelProperty("难度")
    @ColumnWidth(8)
    private Integer difficulty;

    @ExcelProperty("掌握度")
    @ColumnWidth(10)
    private String mastery;

    @ExcelProperty("标签")
    @ColumnWidth(20)
    private String tags;

    @ExcelProperty("来源")
    @ColumnWidth(12)
    private String source;

    @ExcelProperty("录入时间")
    @ColumnWidth(20)
    private String createTime;

    public static WrongQuestionExportVO from(WrongQuestionVO vo, int index) {
        WrongQuestionExportVO export = new WrongQuestionExportVO();
        export.setIndex(index);
        export.setSubject(vo.getSubject());
        export.setQuestionType(vo.getQuestionType());
        export.setContent(vo.getContent());
        export.setAnswer(vo.getAnswer());
        export.setAnalysis(vo.getAnalysis());
        export.setDifficulty(vo.getDifficulty());
        export.setMastery(vo.getMastery());
        export.setTags(vo.getTags() == null || vo.getTags().isEmpty() ? "" : String.join("、", vo.getTags()));
        export.setSource(vo.getSource());
        export.setCreateTime(vo.getCreateTime() == null ? "" : vo.getCreateTime().toString().replace('T', ' '));
        return export;
    }
}
