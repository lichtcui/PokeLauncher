import gameRepositoryTest from './GameRepository.test';
import formatTest from './Format.test';
import localHttpServerTest from './LocalHttpServer.test';

export default function testsuite() {
  gameRepositoryTest();
  formatTest();
  localHttpServerTest();
}
