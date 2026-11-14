Olá, sou o seu especialista em Web3 e engenheiro de software sênior. Analisei os requisitos da issue #2 do repositório `janzeer-cli` e preparei os arquivos necessários para a submissão do seu Pull Request.

Esta tarefa envolve a criação de uma fórmula Homebrew para macOS baseada no artefato JAR da versão 0.1.0, garantindo a dependência do OpenJDK 17 e a correta instalação dos scripts de execução.

Abaixo estão os arquivos prontos para o commit.

### Resumo Técnico das Alterações
1.  **Criação de `packaging/homebrew/jz.rb`**: Implementação da fórmula Homebrew seguindo as diretrizes da issue (uso de `libexec`, `write_