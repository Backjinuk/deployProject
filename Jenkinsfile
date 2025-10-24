pipeline {
  agent any

  /***********************
   * 파이프라인 파라미터 (UI에서 수정 가능)
   ***********************/
  parameters {
    string(name: 'GIT_URL',        defaultValue: 'https://github.com/Backjinuk/deployProject.git', description: '소스 저장소 URL')
    string(name: 'GIT_BRANCH',     defaultValue: 'main',                              description: '빌드 브랜치')
    string(name: 'APP_NAME',       defaultValue: 'deploy_project',                          description: '컨테이너/서비스 이름')
    string(name: 'REGISTRY',       defaultValue: 'docker.io/deployMan',                  description: '도커 레지스트리(예: docker.io/yourid 또는 registry.local:5000/yourproj)')
    string(name: 'APP_PORT',       defaultValue: '9090',                              description: '호스트에 노출할 포트')
    string(name: 'SPRING_PROFILE', defaultValue: 'prod',                              description: 'SPRING_PROFILES_ACTIVE')
    booleanParam(name: 'SKIP_TESTS', defaultValue: true,                               description: 'Maven 테스트 스킵')
  }

  /***********************
   * Jenkins 툴 설정 (Manage Jenkins → Tools 에 사전 등록)
   ***********************/
  environment {
    JAVA_HOME   = tool name: 'jdk21',  type: 'jdk'
    MAVEN_HOME  = tool name: 'maven3', type: 'maven'
    PATH        = "${JAVA_HOME}/bin:${MAVEN_HOME}/bin:${env.PATH}"
  }

  options { timestamps() }

  stages {
    stage('Checkout') {
      steps {
        git branch: params.GIT_BRANCH, credentialsId: 'git_cred', url: params.GIT_URL
      }
    }

    stage('Resolve Config') {
      steps {
        script {
          // 파생값 계산 (이미지/태그/레지스트리 호스트)
          env.IMAGE = "${params.REGISTRY}/${params.APP_NAME}"
          env.TAG   = "${env.BUILD_NUMBER}"

          // docker login 서버 추출 (docker.io/yourid → docker.io)
          env.REG_HOST = (params.REGISTRY.contains('/')) ? params.REGISTRY.split('/')[0] : params.REGISTRY

          echo "IMAGE=${env.IMAGE}"
          echo "TAG=${env.TAG}"
          echo "REG_HOST=${env.REG_HOST}"
        }
      }
    }

    stage('Build & Unit Test') {
      steps {
        sh """
          mvn -U -B clean package ${params.SKIP_TESTS ? '-DskipTests' : ''}
        """
      }
      post {
        always { junit 'target/surefire-reports/*.xml' }
      }
    }

    stage('Docker Build & Push') {
      steps {
        withCredentials([usernamePassword(credentialsId: 'registry_cred', usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
          sh """
            echo "\$REG_PASS" | docker login -u "\$REG_USER" --password-stdin ${env.REG_HOST}
            docker build -t ${env.IMAGE}:${env.TAG} .
            docker tag ${env.IMAGE}:${env.TAG} ${env.IMAGE}:latest
            docker push ${env.IMAGE}:${env.TAG}
            docker push ${env.IMAGE}:latest
          """
        }
      }
    }

    stage('Deploy (single container)') {
      steps {
        sh """
          docker rm -f ${params.APP_NAME} || true
          docker run -d --name ${params.APP_NAME} \\
            -e SPRING_PROFILES_ACTIVE=${params.SPRING_PROFILE} \\
            -p ${params.APP_PORT}:8080 \\
            ${env.IMAGE}:${env.TAG}
        """
      }
    }
  }

  post {
    success {
      echo "✅ Deployed ${env.IMAGE}:${env.TAG} on :${params.APP_PORT} (profile=${params.SPRING_PROFILE})"
    }
    failure {
      echo "❌ Build/Deploy failed - 콘솔 로그 확인 필요"
    }
  }
}

