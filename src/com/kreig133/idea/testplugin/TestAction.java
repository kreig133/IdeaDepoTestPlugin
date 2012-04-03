package com.kreig133.idea.testplugin;

import com.intellij.codeInsight.CodeInsightBundle;
import com.intellij.codeInsight.CodeInsightUtil;
import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.codeInsight.generation.GenerateMembersUtil;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.impl.source.PostprocessReformattingAspect;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.refactoring.PackageWrapper;
import com.intellij.refactoring.util.RefactoringUtil;
import com.intellij.refactoring.util.classMembers.MemberInfo;
import com.intellij.testIntegration.TestFramework;
import com.intellij.testIntegration.TestIntegrationUtils;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * @author kreig133
 * @version 1.0
 */
public class TestAction extends AnAction {
    private Module myTargetModule;
    private Project project;

    @Override
    public final void actionPerformed( final AnActionEvent e ) {

        final PsiJavaFile data = ( PsiJavaFile ) ( e.getData( LangDataKeys.PSI_FILE ) );

        assert data != null;

        project = e.getProject();
        myTargetModule = ModuleUtil.findModuleForPsiElement( data );

        // Копипаста из action'a генерации тестов
        CommandProcessor.getInstance().executeCommand( project, new Runnable() {
            @Override
            public void run() {
                PostprocessReformattingAspect.getInstance( project ).postponeFormattingInside(
                        new Computable<PsiElement>() {
                            @Override
                            public PsiElement compute() {
                                return ApplicationManager.getApplication().runWriteAction(
                                        new Computable<PsiElement>() {
                                            @Override
                                            public PsiElement compute() {
                                                try {
                                                    return generateTestFile( data );
                                                } catch ( IncorrectOperationException ex ) {
                                                    showErrorLater( project, data.getName() );
                                                    return null;
                                                }
                                            }
                                        } );
                            }
                        } );
            }
        }, CodeInsightBundle.message( "intention.create.test" ), this );
    }

    @SuppressWarnings( "ConstantConditions" )
    private PsiElement generateTestFile( final PsiJavaFile data ) {
        IdeDocumentHistory.getInstance( project ).includeCurrentPlaceAsChangePlace();

        final PackageWrapper targetPackage =
                new PackageWrapper( PsiManager.getInstance( project ), data.getPackageName() );

        final PsiClass publicClassFromFile = getPublicClassFromFile( data.getClasses() );

        final VirtualFile directoryForTestGenerating = getJavaTestDirectory( true );

        if ( directoryForTestGenerating == null ) return null;

        final PsiDirectory resultObject = createTestPackageDirectory( targetPackage, directoryForTestGenerating );

        PsiClass targetClass = JavaDirectoryService.getInstance().createClass(
                resultObject, publicClassFromFile.getName() + "Test"
        );

        addSuperClass( targetClass );

        Editor editor = CodeInsightUtil.positionCursor( project, targetClass.getContainingFile(),
                targetClass.getLBrace() );

        addTestMethods(
                editor,
                targetClass,
                getJUnit4TestFramework(),
                TestIntegrationUtils.extractClassMethods(
                        publicClassFromFile, true
                )
        );

        addDaoField( data, targetClass );

        JavaCodeStyleManager.getInstance( project ).shortenClassReferences( targetClass );

        return targetClass;
    }

    private void addDaoField( PsiJavaFile data, PsiClass targetClass ) {
        final PsiElementFactory elementFactory =
                JavaPsiFacade.getInstance( project ).getElementFactory();
        final PsiField dao = elementFactory.createField( "dao",
                elementFactory.createType( getPublicClassFromFile( data.getClasses() ) ) );

        dao.getModifierList().addAnnotation( "org.springframework.beans.factory.annotation.Autowired" );

        GenerateMembersUtil.insert( targetClass, dao, null, false );
    }

    private TestFramework getJUnit4TestFramework() {
        final TestFramework[] extensions = Extensions.getExtensions( TestFramework.EXTENSION_NAME );
        for ( TestFramework extension : extensions ) {
            if( extension.getName().equals( "JUnit4" ) ){
                return extension;
            }
        }
        throw new RuntimeException( "Нет поддержики JUnit4." );
    }

    private PsiClass getPublicClassFromFile( PsiClass[] psiClass ) {
        for ( PsiClass psiClas : psiClass ) {
            if ( psiClas.getModifierList().hasModifierProperty( "public" ) ) {
                return psiClas;
            }
        }
        throw new IllegalArgumentException();
    }

    private PsiDirectory createTestPackageDirectory( final PackageWrapper targetPackage, final VirtualFile directoryForTestGenerating ) {
        return new WriteCommandAction<PsiDirectory>( project,
                CodeInsightBundle.message( "create.directory.command" ) ) {
            @Override
            protected void run( Result<PsiDirectory> result ) throws Throwable {
                result.setResult( RefactoringUtil.createPackageDirectoryInSourceRoot( targetPackage,
                        directoryForTestGenerating ) );
            }
        }.execute().getResultObject();
    }

    private VirtualFile getJavaTestDirectory( boolean source ) {
        VirtualFile[] roots = ModuleRootManager.getInstance( myTargetModule ).getSourceRoots();

        if ( roots.length == 1 ) {
            return roots[ 0 ];
        } else {
            for ( VirtualFile root : roots ) {
                if ( root.getPath().contains( "test" ) ) {
                    if( root.getPath().contains( "java" ) && source ){
                        return root;
                    }
                    if( root.getPath().contains( "resources" ) && ! source ){
                        return root;
                    }
                }
            }
        }
        throw new RuntimeException( "Не нашлось папки для созданий тестовых файлов" );
    }

    private void addTestMethods(
            Editor editor,
            PsiClass targetClass,
            TestFramework descriptor,
            Collection<MemberInfo> methods
    ) throws IncorrectOperationException {

        for ( MemberInfo m : methods ) {
            generateMethod(
                    TestIntegrationUtils.MethodKind.TEST,
                    descriptor, targetClass, editor, m
            );
        }
    }

    private void generateMethod(
            TestIntegrationUtils.MethodKind methodKind,
            TestFramework descriptor,
            PsiClass targetClass,
            Editor editor,
            MemberInfo m
    ) {
        String name = m.getMember().getName();
        PsiMethod method =
                ( PsiMethod ) targetClass.add( TestIntegrationUtils.createDummyMethod( targetClass.getProject() ) );
        PsiDocumentManager.getInstance( targetClass.getProject() ).doPostponedOperationsAndUnblockDocument(
                editor.getDocument() );
        TestIntegrationUtils.runTestMethodTemplate( methodKind, descriptor, editor, targetClass, method, name, true );
        final PsiMethod[] psiMethods = targetClass.getMethods();
        for ( PsiMethod psiMethod : psiMethods ) {
            if ( psiMethod.getName().equalsIgnoreCase( "test" + name ) ) {
                method = psiMethod;
            }
        }
        generateTestMethodBody( m, method.getBody() );
    }

    private void generateTestMethodBody( MemberInfo memberInfo, PsiCodeBlock body ) {

        String map;

        final PsiParameter[] methodParameters =
                ( ( PsiMethod ) memberInfo.getMember() ).getParameterList().getParameters();

        if ( methodParameters.length > 0 ) {

            map = "values";

            insertStatementFromTextToBody( body, "java.util.Map<String, String> values = new java.util.HashMap<String, String>();" );

            if ( methodParameters[ 0 ].getType().getCanonicalText().startsWith( "java" ) ) {
                for ( PsiParameter methodParameter : methodParameters ) {
                    generatePuttingToValuesMap( body, methodParameter.getName() );
                }

            } else {
                PsiClass superClass =
                        findClass( body.getProject(), methodParameters[ 0 ].getType().getCanonicalText() );
                for ( PsiField methodParameter : superClass.getAllFields() ) {
                    if ( ! methodParameter.getName().equals( "serialVersionUID" ) ) {
                        generatePuttingToValuesMap( body, methodParameter.getName() );
                    }
                }
            }
        } else {
            map = "new java.util.HashMap<String, String>()";
        }

        insertStatementFromTextToBody( body, String.format(
                "Object invoke = invoke( dao, %s, \"%s\" );", map, memberInfo.getMember().getName()
        ) );
        insertStatementFromTextToBody( body, "org.junit.Assert.assertNotNull( invoke );" );
    }

    private void insertStatementFromTextToBody( PsiCodeBlock body, String statementText ) {
        body.add(
                JavaPsiFacade.getInstance( project ).getElementFactory().createStatementFromText(
                        statementText , body.getRBrace()
                )
        );
    }

    private void generatePuttingToValuesMap( PsiCodeBlock body, String name ) {
        insertStatementFromTextToBody( body, String.format( "values.put( \"%s\", \"%s\" );", name, "null" ) );
    }

    private void addSuperClass( PsiClass targetClass ) throws IncorrectOperationException {
        PsiElementFactory ef = JavaPsiFacade.getInstance( project ).getElementFactory();

        PsiClass superClass = findClass( project, "com.aplana.sbrf.deposit.AbstractDepoDaoExecuteTest" );

        assert superClass != null;

        PsiJavaCodeReferenceElement superClassRef = ef.createClassReferenceElement( superClass );
        targetClass.getExtendsList().add( superClassRef );
    }

    @Nullable
    private static PsiClass findClass( Project project, String fqName ) {
        GlobalSearchScope scope = GlobalSearchScope.allScope( project );
        return JavaPsiFacade.getInstance( project ).findClass( fqName, scope );
    }

    @Override
    public void update( final AnActionEvent e ) {

        final boolean enabled = isEnabled( e );
        if ( ActionPlaces.isPopupPlace( e.getPlace() ) ) {
            e.getPresentation().setVisible( enabled );
        } else {
            e.getPresentation().setVisible( true );
        }
        e.getPresentation().setEnabled( enabled );
    }

    private boolean isEnabled( final AnActionEvent e ) {
        final Project project = PlatformDataKeys.PROJECT.getData( e.getDataContext() );
        if ( project == null ) return false;

        final Editor editor = PlatformDataKeys.EDITOR.getData( e.getDataContext() );
        if ( editor != null ) {
            final PsiFile file = PsiDocumentManager.getInstance( project ).getPsiFile( editor.getDocument() );
            if ( file == null ) return false;

            final PsiElement targetElement = TargetElementUtil
                    .findTargetElement( editor,
                            TargetElementUtil.ELEMENT_NAME_ACCEPTED | TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED |
                                    TargetElementUtil.LOOKUP_ITEM_ACCEPTED );
            if ( targetElement instanceof PsiClass ) {
                return true;
            }

            final int offset = editor.getCaretModel().getOffset();
            PsiElement element = file.findElementAt( offset );
            while ( element != null ) {
                if ( element instanceof PsiFile ) {
                    if ( ! ( element instanceof PsiClassOwner ) ) return false;
                    final PsiClass[] classes = ( ( PsiClassOwner ) element ).getClasses();
                    return classes.length == 1;
                }
                if ( element instanceof PsiClass && ! ( element instanceof PsiAnonymousClass ) ) {
                    return true;
                }
                element = element.getParent();
            }

            return false;
        } else {
            final PsiElement element = LangDataKeys.PSI_ELEMENT.getData( e.getDataContext() );
            return element instanceof PsiClass;
        }
    }

    private static void showErrorLater( final Project project, final String targetClassName ) {
        ApplicationManager.getApplication().invokeLater( new Runnable() {
            public void run() {
                Messages.showErrorDialog( project,
                        CodeInsightBundle.message( "intention.error.cannot.create.class.message", targetClassName ),
                        CodeInsightBundle.message( "intention.error.cannot.create.class.title" ) );
            }
        } );
    }
}
