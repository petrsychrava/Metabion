package com.metabion.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

public class EducationContentForm {

    @NotBlank
    @Size(max = 120)
    private String slug;

    @NotBlank
    @Size(max = 80)
    private String topic;

    @Min(1)
    private int sortOrder = 10;

    @NotBlank
    @Size(max = 200)
    private String englishTitle;

    @NotBlank
    @Size(max = 1000)
    private String englishSummary;

    @Size(max = 200)
    private String czechTitle;

    @Size(max = 1000)
    private String czechSummary;

    @Valid
    private List<LessonRow> lessons = new ArrayList<>();

    public EducationModuleRequest toModuleRequest() {
        return new EducationModuleRequest(
                slug,
                topic,
                sortOrder,
                englishTitle,
                englishSummary,
                czechTitle,
                czechSummary);
    }

    public List<EducationLessonUpsertRequest> toLessonRequests() {
        return lessonsOrEmpty().stream()
                .filter(row -> !row.isBlank())
                .map(LessonRow::toRequest)
                .toList();
    }

    public void ensureRows(int count) {
        if (lessons == null) {
            lessons = new ArrayList<>();
        }
        while (lessons.size() < count) {
            lessons.add(new LessonRow());
        }
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getEnglishTitle() {
        return englishTitle;
    }

    public void setEnglishTitle(String englishTitle) {
        this.englishTitle = englishTitle;
    }

    public String getEnglishSummary() {
        return englishSummary;
    }

    public void setEnglishSummary(String englishSummary) {
        this.englishSummary = englishSummary;
    }

    public String getCzechTitle() {
        return czechTitle;
    }

    public void setCzechTitle(String czechTitle) {
        this.czechTitle = czechTitle;
    }

    public String getCzechSummary() {
        return czechSummary;
    }

    public void setCzechSummary(String czechSummary) {
        this.czechSummary = czechSummary;
    }

    public List<LessonRow> getLessons() {
        return lessons;
    }

    public void setLessons(List<LessonRow> lessons) {
        this.lessons = lessons == null ? new ArrayList<>() : lessons;
    }

    private List<LessonRow> lessonsOrEmpty() {
        return lessons == null ? List.of() : lessons;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public static class LessonRow {

        @Size(max = 120)
        private String slug;

        private int sortOrder;

        @Size(max = 200)
        private String englishTitle;

        @Size(max = 1000)
        private String englishSummary;

        @Size(max = 20000)
        private String englishBodyMarkdown;

        @Size(max = 200)
        private String czechTitle;

        @Size(max = 1000)
        private String czechSummary;

        @Size(max = 20000)
        private String czechBodyMarkdown;

        public boolean isBlank() {
            return blank(slug)
                    && sortOrder == 0
                    && blank(englishTitle)
                    && blank(englishSummary)
                    && blank(englishBodyMarkdown)
                    && blank(czechTitle)
                    && blank(czechSummary)
                    && blank(czechBodyMarkdown);
        }

        public EducationLessonUpsertRequest toRequest() {
            return new EducationLessonUpsertRequest(
                    slug,
                    sortOrder,
                    englishTitle,
                    englishSummary,
                    englishBodyMarkdown,
                    czechTitle,
                    czechSummary,
                    czechBodyMarkdown);
        }

        @AssertTrue(message = "populated lesson rows must include slug, sortOrder, englishTitle, englishSummary, and englishBodyMarkdown")
        public boolean isCompleteOrBlank() {
            return isBlank()
                    || (!blank(slug)
                    && sortOrder >= 1
                    && !blank(englishTitle)
                    && !blank(englishSummary)
                    && !blank(englishBodyMarkdown));
        }

        public String getSlug() {
            return slug;
        }

        public void setSlug(String slug) {
            this.slug = slug;
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
        }

        public String getEnglishTitle() {
            return englishTitle;
        }

        public void setEnglishTitle(String englishTitle) {
            this.englishTitle = englishTitle;
        }

        public String getEnglishSummary() {
            return englishSummary;
        }

        public void setEnglishSummary(String englishSummary) {
            this.englishSummary = englishSummary;
        }

        public String getEnglishBodyMarkdown() {
            return englishBodyMarkdown;
        }

        public void setEnglishBodyMarkdown(String englishBodyMarkdown) {
            this.englishBodyMarkdown = englishBodyMarkdown;
        }

        public String getCzechTitle() {
            return czechTitle;
        }

        public void setCzechTitle(String czechTitle) {
            this.czechTitle = czechTitle;
        }

        public String getCzechSummary() {
            return czechSummary;
        }

        public void setCzechSummary(String czechSummary) {
            this.czechSummary = czechSummary;
        }

        public String getCzechBodyMarkdown() {
            return czechBodyMarkdown;
        }

        public void setCzechBodyMarkdown(String czechBodyMarkdown) {
            this.czechBodyMarkdown = czechBodyMarkdown;
        }
    }
}
