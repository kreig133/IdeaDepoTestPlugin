package com.kreig133.idea.testplugin;

import com.intellij.psi.PsiClass;

/**
 * @author kreig133
 * @version 1.0
 */
public class TestBeanConfigCreator {
    private static String template =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<beans xmlns=\"http://www.springframework.org/schema/beans\"\n" +
            "       xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
            "       xsi:schemaLocation=\"http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans-3.0.xsd\">\n" +
            "\n" +
            "    <import resource=\"classpath:testApplicationContext.xml\"/>\n" +
            "    <!-- Data Layer -->\n" +
            "\n" +
            "    <bean id=\"${beanName}\" class=\"org.mybatis.spring.mapper.MapperFactoryBean\">\n" +
            "        <property name=\"mapperInterface\"\n" +
            "                  value=\"${beanClass}\"/>\n" +
            "        <property name=\"sqlSessionFactory\" ref=\"sqlSessionFactory\"/>\n" +
            "    </bean>\n" +
            "</beans>";

    static String getBeanConfig( PsiClass psiClass ){
        return template
                .replaceFirst( "\\$\\{beanClass\\}", psiClass.getQualifiedName() )
                .replaceFirst( "\\$\\{beanName\\}", psiClass.getName() );
    }

}
