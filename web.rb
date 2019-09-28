require 'sidekiq'
require 'sinatra/base'
require_relative 'worker'
require 'securerandom'
require 'json'

Sidekiq.configure_client do |config|
  config.redis = { url: ENV['REDIS_URL'] }
end

$redis = Redis.new(url: ENV['REDIS_URL'])

$stdout.sync = true

class Web < Sinatra::Application

  before do
    content_type 'application/json'
  end

  get '/search' do
    req_id = SecureRandom.uuid
    account = params[:account]
    device_token = params[:device_token]

    if account.nil? || account.empty?
      puts 'ERROR - account empty'
      halt 400, 'account empty'
      return
    end

    if device_token.nil? || device_token.empty?
      puts 'ERROR - device_token empty'
      halt 400, 'device_token empty'
      return
    end

    key = "data_#{req_id}"
    #puts "#{key} #{account} - #{device_token}"
    $redis.set(key, [account, device_token].to_json)
    BgWorker.perform_async(req_id)
    "enqueued request for #{device_token}"
  end

  get '/conf' do
    puts "request ip: #{request.ip}"
    return {
      REDIS_URL: ENV['REDIS_URL']
    }.to_json
  end

  get '/ping' do
    BgWorker.perform_async('__PING__')
    return {
      status: :ok
    }.to_json
  end

  get '/monitor' do
    last_ping_ms = $redis.get(:worker_status) || '0'
    now_ms = Time.now.to_i
    status = (now_ms - last_ping_ms.to_i) < 6 * 60 * 1000
    if status
      status 200
    else
      status 500
    end
  end
end
