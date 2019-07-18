require './web'
require 'sidekiq/web'

run Rack::URLMap.new(
  '/' => Web,
  # '/sidekiq' => Sidekiq::Web
)
