require 'logstash/devutils/rake'

task :install_jars do
  sh('./gradlew clean vendor test')
end
task :prepare_geoip_resources do
  sh('./gradlew geoipTestResources')
end

task :vendor => :install_jars

task :test do
  require 'rspec'
  require 'rspec/core/runner'
  Rake::Task[:install_jars].invoke
  Rake::Task[:generate_ssl].invoke
  sh './gradlew test'
  exit(RSpec::Core::Runner.run(Rake::FileList['spec/**/*_spec.rb']))
end