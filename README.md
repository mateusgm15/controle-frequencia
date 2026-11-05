# Controle de Frequência

Sistema em Java para registrar presença e falta de alunos, com consulta por dia e front end web.
Desenvolvido como atividade de extensão (ADS, UNIP).

## Tecnologias
Java 17, HttpServer do JDK, JSON, HTML/CSS/JavaScript. Sem dependências externas.

## Como rodar
mkdir out
javac -encoding UTF-8 -d out src/Main.java
cp src/index.html out/
java -cp out Main

Abre em http://localhost:8080

## Funções
- Cadastro de nome, turma, data e presença/falta
- Consulta por dia e por turma, com totais
- Alterar status, excluir e exportar CSV
- Bloqueio de registro duplicado

## Limitações
Sem login, dados em arquivo de texto e sem cadastro prévio de alunos.

## Próximos passos
Cadastro de alunos e turmas, login, banco de dados e migração para Spring Boot.
