package com.example.agent.service.company;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.*;

@Component
public class CompanyProfileCatalog {
    private final JsonNode regions;
    public static final List<String> INDUSTRIES=List.of("互联网与软件","人工智能","电子与通信","制造业","金融","教育","医疗健康","物流与供应链","零售与电商","专业服务","其他");
    public static final List<String> SCALES=List.of("1～20人","21～99人","100～499人","500～999人","1000～9999人","10000人以上");
    public static final List<String> NATURES=List.of("民营企业","国有企业","外资企业","合资企业","事业单位","社会组织","其他");
    private static final Set<String> FIELDS=Set.of("shortName","industry","scale","nature","provinceCode","cityCode","provinceName","cityName","address","website","contactName","contactEmail","contactPhone");
    public CompanyProfileCatalog(ObjectMapper json) throws java.io.IOException {
        try (var input=new ClassPathResource("company-regions.json").getInputStream()) { regions=json.readTree(input); }
    }
    public Map<String,Object> options() { return Map.of("regions",regions,"industries",INDUSTRIES,"scales",SCALES,"natures",NATURES); }
    public Map<String,String> validate(Map<String,String> input) {
        if (input==null || !FIELDS.containsAll(input.keySet())) throw invalid("请完善公司行业、规模和所在地区");
        var result=new LinkedHashMap<String,String>();
        input.forEach((key,value)->result.put(key,value==null ? "" : value.trim()));
        if (!INDUSTRIES.contains(result.getOrDefault("industry","")) || !SCALES.contains(result.getOrDefault("scale",""))) throw invalid("请选择有效的行业和公司规模");
        if (!result.getOrDefault("nature","").isEmpty() && !NATURES.contains(result.get("nature"))) throw invalid("公司性质无效");
        JsonNode province=null,city=null;
        for (var region:regions) if (region.path("value").asText().equals(result.get("provinceCode"))) province=region;
        if (province!=null) for (var value:province.path("children")) if (value.path("value").asText().equals(result.get("cityCode"))) city=value;
        if (province==null || city==null) throw invalid("请选择相匹配的省份和城市");
        result.put("provinceName",province.path("label").asText());result.put("cityName",city.path("label").asText());
        for (var entry:result.entrySet()) {
            int max=switch(entry.getKey()) { case "address","website"->500;case "contactEmail"->254;case "contactPhone"->30;default->100; };
            if (entry.getValue().length()>max) throw invalid("公司资料字段超出允许长度");
        }
        String website=result.getOrDefault("website","");
        if (!website.isEmpty()) {
            try {
                var uri=URI.create(website);
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost()==null || uri.getUserInfo()!=null) throw invalid("官网须为有效的http或https网址");
            } catch (IllegalArgumentException e) { throw invalid("官网须为有效的http或https网址"); }
        }
        String email=result.getOrDefault("contactEmail","");
        if (!email.isEmpty() && !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw invalid("招聘联系邮箱格式不正确");
        String phone=result.getOrDefault("contactPhone","");
        if (!phone.isEmpty() && !phone.matches("[+0-9()（）\\-\\s]{5,30}")) throw invalid("招聘联系电话格式不正确");
        return result;
    }
    private IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
